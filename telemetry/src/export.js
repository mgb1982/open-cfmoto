// SPDX-License-Identifier: AGPL-3.0-or-later
// RideScreen AA — read-only export of crash/error reports for the private reports repo.
//
//   GET /v1/export?since=<ms>   Authorization: Bearer <EXPORT_TOKEN>
//
// Returns install stats, every failure fingerprint and the events since `since` (max 500, oldest
// first) with their payloads (already redacted by the app). Install UUIDs are replaced by a short
// salted hash so a phone can be followed across reports without exposing its id. Off (404) when the
// EXPORT_TOKEN secret is not set.

const MAX_EVENTS = 500;
const DAY = 86_400_000;

export async function handleExport(request, env, url) {
  if (!env.EXPORT_TOKEN) return json({ error: "off" }, 404);
  const got = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if (!(await sameSecret(got, env.EXPORT_TOKEN))) return json({ error: "unauthorized" }, 401);

  const now = Date.now();
  const since = Math.max(0, Number(url.searchParams.get("since")) || 0);
  const one = (sql, ...b) => env.DB.prepare(sql).bind(...b).first();
  const all = async (sql, ...b) => (await env.DB.prepare(sql).bind(...b).all()).results;

  const installs = {
    total: (await one("SELECT COUNT(*) AS n FROM installs")).n,
    active1d: (await one("SELECT COUNT(*) AS n FROM installs WHERE last_seen >= ?", now - DAY)).n,
    active7d: (await one("SELECT COUNT(*) AS n FROM installs WHERE last_seen >= ?", now - 7 * DAY)).n,
    active30d: (await one("SELECT COUNT(*) AS n FROM installs WHERE last_seen >= ?", now - 30 * DAY)).n,
    byVersion: await all("SELECT version, COUNT(*) AS n FROM installs WHERE last_seen >= ? GROUP BY version ORDER BY n DESC", now - 30 * DAY),
    bySdk: await all("SELECT sdk, COUNT(*) AS n FROM installs WHERE last_seen >= ? GROUP BY sdk ORDER BY sdk", now - 30 * DAY),
    byStore: await all("SELECT store, COUNT(*) AS n FROM installs WHERE last_seen >= ? GROUP BY store", now - 30 * DAY),
    byLocale: await all("SELECT locale, COUNT(*) AS n FROM installs WHERE last_seen >= ? GROUP BY locale ORDER BY n DESC", now - 30 * DAY),
  };
  const fingerprints = await all(
    "SELECT f.*, (SELECT COUNT(DISTINCT uuid) FROM events e WHERE e.fp = f.id) AS phones FROM fingerprints f ORDER BY last_seen DESC",
  );
  const rows = await all(
    "SELECT id, ts, uuid, type, version, sdk, fp, payload FROM events WHERE ts > ? ORDER BY ts ASC LIMIT ?",
    since, MAX_EVENTS,
  );
  const events = [];
  for (const r of rows) {
    let payload = r.payload;
    try { payload = JSON.parse(r.payload); } catch (_) {}
    events.push({ id: r.id, ts: r.ts, phone: await shortHash(r.uuid, env.EXPORT_TOKEN), type: r.type, version: r.version, sdk: r.sdk, fp: r.fp, payload });
  }
  return json({
    generatedAt: now,
    since,
    more: rows.length === MAX_EVENTS,
    next: rows.length ? rows[rows.length - 1].ts : since,
    installs,
    fingerprints,
    events,
  });
}

async function sha(s) {
  const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return new Uint8Array(h);
}

async function shortHash(uuid, salt) {
  const b = await sha("phone|" + salt + "|" + uuid);
  return [...b.slice(0, 4)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

// Compare digests so the check takes the same time whatever the input.
async function sameSecret(a, b) {
  const [x, y] = await Promise.all([sha("x|" + a), sha("x|" + b)]);
  let d = 0;
  for (let i = 0; i < x.length; i++) d |= x[i] ^ y[i];
  return d === 0 && a.length > 0;
}

function json(o, status = 200) {
  return new Response(JSON.stringify(o), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}
