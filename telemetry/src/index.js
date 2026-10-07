// SPDX-License-Identifier: AGPL-3.0-or-later
// RideScreen AA — anonymous usage & crash-report receiver (Cloudflare Worker + D1).
//
//   POST /v1/ping        what the app sends (AnonymousTelemetry.kt): {uuid,type,version,versionCode,
//                        androidSdk,locale,payload?}; type = ping | crash | error
//   POST /tg/<secret>    Telegram webhook (bot commands, only from TELEGRAM_CHAT_ID)
//   GET  /               health check
//   cron                 weekly summary to Telegram + 180-day retention cleanup
//
// Privacy: no IP, no country, no headers are stored — only what the app sends, which is a random
// UUID, version info and an already-redacted crash/error text.

const TYPES = new Set(["ping", "crash", "error"]);
const MAX_BODY = 64 * 1024;
const MAX_PAYLOAD = 40_000;
const MAX_EVENTS_PER_UUID_HOUR = 30;
const MAX_ALERTS_PER_HOUR = 20;
const RETENTION_DAYS = 180;
// Re-alert on the Nth occurrence of the same fingerprint (1st = full alert).
const REALERT_AT = new Set([5, 25, 100, 500]);

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === "POST" && url.pathname === "/v1/ping") return await ingest(request, env, ctx);
      if (request.method === "POST" && url.pathname.startsWith("/tg/")) return await telegramWebhook(request, env, url);
      if (request.method === "GET" && url.pathname === "/") return text("RideScreen AA telemetry: ok");
      return text("not found", 404);
    } catch (e) {
      console.log("unhandled", e && e.stack);
      return text("error", 500);
    }
  },

  async scheduled(event, env, ctx) {
    const now = Date.now();
    const cutoff = now - RETENTION_DAYS * 86_400_000;
    await env.DB.batch([
      env.DB.prepare("DELETE FROM events WHERE ts < ?").bind(cutoff),
      env.DB.prepare("DELETE FROM installs WHERE last_seen < ?").bind(cutoff),
      env.DB.prepare("DELETE FROM fingerprints WHERE last_seen < ?").bind(cutoff),
      env.DB.prepare("DELETE FROM rate WHERE bucket < ?").bind(hourBucket(now) - 48),
    ]);
    await tgSend(env, "🗓 <b>Resumen semanal</b>\n\n" + (await summary(env, 7)));
  },
};

// ---------------------------------------------------------------- ingest

async function ingest(request, env, ctx) {
  const len = Number(request.headers.get("content-length") || 0);
  if (len > MAX_BODY) return text("too large", 413);
  const raw = await request.text();
  if (raw.length > MAX_BODY) return text("too large", 413);
  let b;
  try {
    b = JSON.parse(raw);
  } catch {
    return text("bad json", 400);
  }
  const uuid = String(b.uuid || "");
  const type = String(b.type || "");
  if (!/^[0-9a-f-]{36}$/i.test(uuid) || !TYPES.has(type)) return text("bad request", 400);
  const version = clip(b.version, 40);
  const versionCode = toInt(b.versionCode);
  const sdk = toInt(b.androidSdk);
  const locale = clip(b.locale, 20);
  // null when an older build doesn't send it: keep what we already know about this install.
  const store = ["github", "play"].includes(b.store) ? b.store : null;
  const payload = type === "ping" ? "" : clip(b.payload, MAX_PAYLOAD);
  const now = Date.now();

  // Per-install rate limit (a stuck loop on one phone must not flood the DB or Telegram).
  const bucket = hourBucket(now);
  const r = await env.DB.prepare(
    "INSERT INTO rate (key, bucket, n) VALUES (?, ?, 1) ON CONFLICT(key, bucket) DO UPDATE SET n = n + 1 RETURNING n"
  ).bind("u:" + uuid, bucket).first();
  if (r && r.n > MAX_EVENTS_PER_UUID_HOUR) return text("slow down", 429);

  const isNew = !(await env.DB.prepare("SELECT 1 FROM installs WHERE uuid = ?").bind(uuid).first());
  const stmts = [
    env.DB.prepare(
      `INSERT INTO installs (uuid, first_seen, last_seen, version, version_code, sdk, locale, store)
       VALUES (?1, ?2, ?2, ?3, ?4, ?5, ?6, COALESCE(?7, 'github'))
       ON CONFLICT(uuid) DO UPDATE SET last_seen = ?2, version = ?3, version_code = ?4, sdk = ?5, locale = ?6,
         store = COALESCE(?7, store)`
    ).bind(uuid, now, version, versionCode, sdk, locale, store),
  ];

  let fp = null;
  if (type !== "ping" && payload) {
    fp = await fingerprint(type, payload);
    stmts.push(
      env.DB.prepare(
        "INSERT INTO events (ts, uuid, type, version, sdk, fp, payload) VALUES (?, ?, ?, ?, ?, ?, ?)"
      ).bind(now, uuid, type, version, sdk, fp.id, payload),
      env.DB.prepare(
        `INSERT INTO fingerprints (id, type, title, first_seen, last_seen, count, last_version)
         VALUES (?1, ?2, ?3, ?4, ?4, 1, ?5)
         ON CONFLICT(id) DO UPDATE SET last_seen = ?4, count = count + 1, last_version = ?5`
      ).bind(fp.id, type, fp.title, now, version)
    );
  }
  await env.DB.batch(stmts);

  if (fp) {
    const row = await env.DB.prepare("SELECT count FROM fingerprints WHERE id = ?").bind(fp.id).first();
    const n = row ? row.count : 1;
    if (n === 1 || REALERT_AT.has(n)) ctx.waitUntil(alert(env, type, fp, n, version, sdk, payload));
  }
  if (isNew && type === "ping") ctx.waitUntil(newInstallNote(env, version));
  return text("ok");
}

async function alert(env, type, fp, n, version, sdk, payload) {
  if (!(await alertBudget(env))) return;
  const icon = type === "crash" ? "💥" : "⚠️";
  const head = n === 1
    ? `${icon} <b>${type === "crash" ? "Cierre inesperado nuevo" : "Error nuevo"}</b>`
    : `${icon} <b>Se repite</b> (${n} veces)`;
  const msg =
    `${head}\n<code>${esc(fp.title)}</code>\n` +
    `v${esc(version)} · Android API ${sdk}\n` +
    `id <code>${fp.id}</code> — /ver_${fp.id}` +
    (n === 1 ? `\n\n<pre>${esc(payload.slice(0, 2500))}</pre>` : "");
  await tgSend(env, msg);
}

// One short line per new install, at most a few per day so it stays pleasant, not noisy.
async function newInstallNote(env) {
  const day = Math.floor(Date.now() / 86_400_000);
  const r = await env.DB.prepare(
    "INSERT INTO rate (key, bucket, n) VALUES ('newinst', ?, 1) ON CONFLICT(key, bucket) DO UPDATE SET n = n + 1 RETURNING n"
  ).bind(day).first();
  if (r && r.n > 5) return;
  const t = await env.DB.prepare("SELECT COUNT(*) AS n FROM installs").first();
  await tgSend(env, `🏍 Nueva instalación activa (total: ${t ? t.n : "?"})`, true);
}

async function alertBudget(env) {
  const r = await env.DB.prepare(
    "INSERT INTO rate (key, bucket, n) VALUES ('alerts', ?, 1) ON CONFLICT(key, bucket) DO UPDATE SET n = n + 1 RETURNING n"
  ).bind(hourBucket(Date.now())).first();
  return !r || r.n <= MAX_ALERTS_PER_HOUR;
}

// Groups the same failure across phones/versions: exception class + first app frames, with
// numbers and line numbers stripped so a new build doesn't look like a new bug.
async function fingerprint(type, payload) {
  const lines = payload.split("\n").map((l) => l.trim()).filter(Boolean);
  let title;
  let frames = [];
  if (type === "crash") {
    title = lines.find((l) => /(Exception|Error)\b/.test(l) && !l.startsWith("at ")) || lines[0] || "?";
    const at = lines.filter((l) => l.startsWith("at "));
    frames = at.filter((l) => l.includes("dev.zanderp")).slice(0, 4);
    if (frames.length === 0) frames = at.slice(0, 4);
  } else {
    title = lines[0] || "?";
  }
  title = title.replace(/^.*?(\w+(Exception|Error))/, "$1").slice(0, 160);
  const norm = (title + "|" + frames.join("|"))
    .replace(/:\d+\)/g, ")")
    .replace(/\b0x[0-9a-f]+\b/gi, "#")
    .replace(/\d+/g, "#");
  const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(type + "|" + norm));
  const id = [...new Uint8Array(h)].slice(0, 4).map((x) => x.toString(16).padStart(2, "0")).join("");
  return { id, title };
}

// ---------------------------------------------------------------- Telegram

async function telegramWebhook(request, env, url) {
  if (!env.TELEGRAM_BOT_TOKEN) return text("off", 404);
  const secret = await webhookSecret(env.TELEGRAM_BOT_TOKEN);
  if (url.pathname !== "/tg/" + secret) return text("not found", 404);
  const u = await request.json().catch(() => null);
  const m = u && (u.message || u.edited_message);
  if (!m || !m.text) return text("ok");
  if (String(m.chat.id) !== String(env.TELEGRAM_CHAT_ID)) {
    await tgSend(env, "Este bot es privado.", false, m.chat.id);
    return text("ok");
  }
  const [cmdRaw, ...args] = m.text.trim().split(/\s+/);
  const cmd = cmdRaw.split("@")[0].toLowerCase();
  let reply;
  if (cmd === "/resumen" || cmd === "/start") {
    const days = Math.min(Math.max(toInt(args[0]) || 7, 1), 180);
    reply = `📊 <b>Últimos ${days} días</b>\n\n` + (await summary(env, days));
  } else if (cmd === "/fallos") {
    reply = await topFailures(env, Math.min(Math.max(toInt(args[0]) || 30, 1), 180));
  } else if (cmd === "/versiones") {
    reply = await versions(env);
  } else if (cmd === "/ver" || cmd.startsWith("/ver_")) {
    const id = (cmd.startsWith("/ver_") ? cmd.slice(5) : args[0] || "").toLowerCase();
    return await showFailure(env, id);
  } else {
    reply =
      "Comandos:\n/resumen [días] — usuarios, versiones y fallos\n/fallos [días] — fallos agrupados\n" +
      "/ver_&lt;id&gt; — texto completo de un fallo\n/versiones — instalaciones por versión";
  }
  await tgSend(env, reply);
  return text("ok");
}

async function summary(env, days) {
  const now = Date.now();
  const since = now - days * 86_400_000;
  const q = (sql, ...b) => env.DB.prepare(sql).bind(...b).first();
  const [d1, dN, d30, total, newN, crashes, errors, phones] = await Promise.all([
    q("SELECT COUNT(*) n FROM installs WHERE last_seen >= ?", now - 86_400_000),
    q("SELECT COUNT(*) n FROM installs WHERE last_seen >= ?", since),
    q("SELECT COUNT(*) n FROM installs WHERE last_seen >= ?", now - 30 * 86_400_000),
    q("SELECT COUNT(*) n FROM installs"),
    q("SELECT COUNT(*) n FROM installs WHERE first_seen >= ?", since),
    q("SELECT COUNT(*) n FROM events WHERE type='crash' AND ts >= ?", since),
    q("SELECT COUNT(*) n FROM events WHERE type='error' AND ts >= ?", since),
    q("SELECT COUNT(DISTINCT uuid) n FROM events WHERE ts >= ?", since),
  ]);
  const top = await env.DB.prepare(
    `SELECT e.fp, f.type, f.title, COUNT(*) n FROM events e JOIN fingerprints f ON f.id = e.fp
     WHERE e.ts >= ? GROUP BY e.fp ORDER BY n DESC LIMIT 3`
  ).bind(since).all();
  let s =
    `👥 Activos: ${d1.n} hoy · ${dN.n} en ${days} d · ${d30.n} en 30 d\n` +
    `🆕 Nuevos: ${newN.n} · total registrados: ${total.n}\n` +
    `💥 Cierres: ${crashes.n} · ⚠️ errores: ${errors.n} (en ${phones.n} móviles)`;
  if (top.results.length) {
    s += "\n\nMás frecuentes:";
    for (const r of top.results) s += `\n${r.type === "crash" ? "💥" : "⚠️"} ${r.n}× ${esc(r.title.slice(0, 70))} /ver_${r.fp}`;
  }
  s += "\n\n" + (await versions(env, 4));
  const stores = await env.DB.prepare(
    "SELECT store, COUNT(*) n FROM installs WHERE last_seen >= ? GROUP BY store ORDER BY n DESC"
  ).bind(now - 30 * 86_400_000).all();
  if (stores.results.some((r) => r.store === "play")) {
    s += "\nOrigen (30 d): " + stores.results.map((r) => `${r.store === "play" ? "Play Store" : "GitHub"} ${r.n}`).join(" · ");
  }
  return s;
}

async function versions(env, limit = 10) {
  const rows = await env.DB.prepare(
    `SELECT version, COUNT(*) n FROM installs WHERE last_seen >= ? GROUP BY version ORDER BY n DESC LIMIT ?`
  ).bind(Date.now() - 30 * 86_400_000, limit).all();
  if (!rows.results.length) return "Versiones (30 d): sin datos todavía";
  return "Versiones (30 d): " + rows.results.map((r) => `v${esc(r.version || "?")} ${r.n}`).join(" · ");
}

async function topFailures(env, days) {
  const rows = await env.DB.prepare(
    `SELECT e.fp, f.type, f.title, COUNT(*) n, COUNT(DISTINCT e.uuid) phones, MAX(e.version) v
     FROM events e JOIN fingerprints f ON f.id = e.fp WHERE e.ts >= ?
     GROUP BY e.fp ORDER BY n DESC LIMIT 15`
  ).bind(Date.now() - days * 86_400_000).all();
  if (!rows.results.length) return `✅ Ningún fallo en ${days} días.`;
  let s = `<b>Fallos en ${days} días</b>`;
  for (const r of rows.results) {
    s += `\n\n${r.type === "crash" ? "💥" : "⚠️"} <b>${r.n}×</b> en ${r.phones} móvil(es), última v${esc(r.v || "?")}\n` +
      `<code>${esc(r.title.slice(0, 120))}</code>\n/ver_${r.fp}`;
  }
  return s;
}

async function showFailure(env, id) {
  if (!/^[0-9a-f]{8}$/.test(id)) {
    await tgSend(env, "Uso: /ver_&lt;id&gt; (el id sale en /fallos)");
    return text("ok");
  }
  const f = await env.DB.prepare("SELECT * FROM fingerprints WHERE id = ?").bind(id).first();
  const e = await env.DB.prepare("SELECT * FROM events WHERE fp = ? ORDER BY ts DESC LIMIT 1").bind(id).first();
  if (!f || !e) {
    await tgSend(env, "No encuentro ese fallo (puede haber caducado).");
    return text("ok");
  }
  const head =
    `${f.type === "crash" ? "💥" : "⚠️"} <code>${esc(f.title)}</code>\n` +
    `${f.count} veces · primera ${fmtDate(f.first_seen)} · última ${fmtDate(f.last_seen)}\n` +
    `Último: v${esc(e.version || "?")} · Android API ${e.sdk}`;
  if (e.payload.length <= 3300) {
    await tgSend(env, `${head}\n\n<pre>${esc(e.payload)}</pre>`);
  } else {
    await tgSend(env, head + "\n\nTexto completo en el archivo adjunto.");
    await tgDocument(env, `fallo-${id}.txt`, e.payload);
  }
  return text("ok");
}

async function tgSend(env, html, silent = false, chatId = env.TELEGRAM_CHAT_ID) {
  if (!env.TELEGRAM_BOT_TOKEN || !chatId) return;
  const r = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      chat_id: chatId,
      text: html.slice(0, 4000),
      parse_mode: "HTML",
      disable_web_page_preview: true,
      disable_notification: silent,
    }),
  });
  if (!r.ok) console.log("telegram", r.status, await r.text());
}

async function tgDocument(env, name, content) {
  const form = new FormData();
  form.append("chat_id", String(env.TELEGRAM_CHAT_ID));
  form.append("document", new Blob([content], { type: "text/plain" }), name);
  await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendDocument`, { method: "POST", body: form });
}

// The webhook path is derived from the bot token, so no extra secret has to be configured.
export async function webhookSecret(token) {
  const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("ridescreen-tg|" + token));
  return [...new Uint8Array(h)].slice(0, 16).map((x) => x.toString(16).padStart(2, "0")).join("");
}

// ---------------------------------------------------------------- helpers

function text(body, status = 200) {
  return new Response(body, { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}
const clip = (v, n) => (v == null ? "" : String(v)).slice(0, n);
const toInt = (v) => (Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : 0);
const hourBucket = (ms) => Math.floor(ms / 3_600_000);
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const fmtDate = (ms) =>
  new Date(ms).toLocaleString("es-ES", { timeZone: "Europe/Madrid", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
