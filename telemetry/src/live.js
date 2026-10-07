// SPDX-License-Identifier: AGPL-3.0-or-later
// RideScreen AA — "share my ride live": a temporary link that shows where the rider is.
//
//   POST /v1/live                 start → {id, token, url}            (from the app)
//   POST /v1/live/<id>            position {lat,lon,spd,ts} + Bearer token (from the app, ~every 10 s)
//   POST /v1/live/<id>/end        stop sharing (Bearer token)
//   GET  /v1/live/<id>/pos        latest position + short trail (the page polls this)
//   GET  /l/<id>                  the page people open from WhatsApp
//
// A session lives at most MAX_HOURS; positions are deleted DELETE_AFTER_END_H after it ends.

const MAX_HOURS = 12;
const DELETE_AFTER_END_H = 24;
const TRAIL_POINTS = 120;
const MIN_UPDATE_MS = 4_000;

export async function handleLive(request, env, url) {
  const p = url.pathname;
  if (request.method === "GET" && p.startsWith("/l/")) return page(p.slice(3));
  if (request.method === "POST" && p === "/v1/live") return start(request, env, url);
  const m = p.match(/^\/v1\/live\/([A-Za-z0-9]{12})(\/end|\/pos)?$/);
  if (!m) return null;
  const [, id, tail] = m;
  if (request.method === "GET" && tail === "/pos") return pos(env, id, Number(url.searchParams.get("since")) || 0);
  if (request.method === "POST" && !tail) return update(request, env, id);
  if (request.method === "POST" && tail === "/end") return end(request, env, id);
  return null;
}

export async function cleanupLive(env) {
  const now = Date.now();
  const old = now - DELETE_AFTER_END_H * 3_600_000;
  // Ended (or expired) sessions older than a day: drop them with their points.
  const gone = await env.DB.prepare(
    "SELECT id FROM live WHERE (ended_at IS NOT NULL AND ended_at < ?1) OR expires_at < ?1"
  ).bind(old).all();
  for (const r of gone.results) {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM live_points WHERE id = ?").bind(r.id),
      env.DB.prepare("DELETE FROM live WHERE id = ?").bind(r.id),
    ]);
  }
}

async function start(request, env, url) {
  const b = await request.json().catch(() => ({}));
  const name = String(b.name || "").slice(0, 40);
  const now = Date.now();
  // Abuse guard without storing IPs: a global cap of new links per hour.
  const r = await env.DB.prepare(
    "INSERT INTO rate (key, bucket, n) VALUES ('live', ?, 1) ON CONFLICT(key, bucket) DO UPDATE SET n = n + 1 RETURNING n"
  ).bind(Math.floor(now / 3_600_000)).first();
  if (r && r.n > 200) return json({ error: "busy" }, 429);
  const id = randomId(12);
  const token = randomId(32);
  await env.DB.prepare(
    "INSERT INTO live (id, token_hash, name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)"
  ).bind(id, await sha(token), name, now, now + MAX_HOURS * 3_600_000).run();
  return json({ id, token, url: `${url.origin}/l/${id}`, expiresAt: now + MAX_HOURS * 3_600_000 });
}

async function auth(request, env, id) {
  const row = await env.DB.prepare("SELECT * FROM live WHERE id = ?").bind(id).first();
  if (!row) return null;
  const h = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if (!h || (await sha(h)) !== row.token_hash) return null;
  return row;
}

async function update(request, env, id) {
  const row = await auth(request, env, id);
  if (!row) return json({ error: "unknown" }, 404);
  const now = Date.now();
  if (row.ended_at || now > row.expires_at) return json({ ended: true }, 410);
  if (row.last_ts && now - row.last_update < MIN_UPDATE_MS) return json({ ok: true, throttled: true });
  const b = await request.json().catch(() => null);
  const lat = Number(b && b.lat), lon = Number(b && b.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
    return json({ error: "bad position" }, 400);
  }
  const spd = Math.max(0, Math.min(400, Math.round(Number(b.spd) || 0)));
  const ts = Number.isFinite(Number(b.ts)) ? Math.min(Number(b.ts), now) : now;
  await env.DB.batch([
    env.DB.prepare(
      "UPDATE live SET lat = ?, lon = ?, spd = ?, last_ts = ?, last_update = ? WHERE id = ?"
    ).bind(lat, lon, spd, ts, now, id),
    env.DB.prepare("INSERT INTO live_points (id, ts, lat, lon) VALUES (?, ?, ?, ?)").bind(id, ts, lat, lon),
  ]);
  return json({ ok: true, expiresAt: row.expires_at });
}

async function end(request, env, id) {
  const row = await auth(request, env, id);
  if (!row) return json({ error: "unknown" }, 404);
  await env.DB.prepare("UPDATE live SET ended_at = ? WHERE id = ? AND ended_at IS NULL").bind(Date.now(), id).run();
  return json({ ok: true });
}

async function pos(env, id, since = 0) {
  const row = await env.DB.prepare("SELECT * FROM live WHERE id = ?").bind(id).first();
  if (!row) return json({ gone: true }, 404);
  const now = Date.now();
  const ended = !!row.ended_at || now > row.expires_at;
  // Only the points the page doesn't have yet (it sends the last ts it drew).
  const trail = await env.DB.prepare(
    "SELECT lat, lon, ts FROM live_points WHERE id = ? AND ts > ? ORDER BY ts DESC LIMIT ?"
  ).bind(id, since, TRAIL_POINTS).all();
  return json(
    {
      name: row.name,
      lat: row.lat,
      lon: row.lon,
      spd: row.spd,
      ts: row.last_ts,
      ended,
      trail: trail.results.reverse().map((r) => [r.lat, r.lon]),
      last: trail.results.length ? trail.results[0].ts : since,
    },
    200,
    { "cache-control": "no-store", "access-control-allow-origin": "*" },
  );
}

function page(id) {
  if (!/^[A-Za-z0-9]{12}$/.test(id)) return new Response("not found", { status: 404 });
  const html = `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>En ruta · RideScreen AA</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
:root{--bg:#18211B;--card:#243028;--gold:#D1A955;--text:#F1F4EC;--muted:#AEBAAA}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
#map{position:absolute;inset:0}
.card{position:absolute;left:12px;right:12px;bottom:max(12px,env(safe-area-inset-bottom));z-index:1000;background:var(--card);border:1px solid #D1A95555;border-radius:18px;padding:14px 16px;box-shadow:0 6px 24px #0008}
.brand{font-weight:900;letter-spacing:.02em}.brand b{color:var(--gold)}
.who{font-size:20px;font-weight:800;margin:4px 0 2px}.row{color:var(--muted);font-size:14px}
.spd{color:var(--gold);font-weight:800}
.dot{width:18px;height:18px;border-radius:50%;background:var(--gold);border:3px solid #fff;box-shadow:0 0 0 6px #D1A95544}
</style></head><body>
<div id="map"></div>
<div class="card"><div class="brand">RideScreen <b>AA</b></div>
<div class="who" id="who">Cargando…</div><div class="row" id="row"></div></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const id=${JSON.stringify(id)};
const map=L.map('map',{zoomControl:false}).setView([41.39,2.16],13);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
const icon=L.divIcon({className:'',html:'<div class="dot"></div>',iconSize:[18,18],iconAnchor:[9,9]});
let marker=null,line=null,first=true,timer=null,since=0,pts=[];
function ago(ts){const s=Math.max(0,Math.round((Date.now()-ts)/1000));if(s<60)return 'hace '+s+' s';const m=Math.round(s/60);return m<60?'hace '+m+' min':'hace '+Math.round(m/60)+' h'}
async function tick(){
  try{
    const r=await fetch('/v1/live/'+id+'/pos?since='+since,{cache:'no-store'});
    if(r.status===404){who.textContent='Este enlace ya no existe';row.textContent='';stopPolling();return}
    const d=await r.json();
    who.textContent=(d.name||'Motorista')+(d.ended?' ha terminado el viaje':' está en ruta');
    if(d.lat==null){row.textContent='Esperando la primera posición…';return}
    const ll=[d.lat,d.lon];
    if(!marker)marker=L.marker(ll,{icon}).addTo(map);else marker.setLatLng(ll);
    if(d.trail&&d.trail.length){pts=pts.concat(d.trail).slice(-600);since=d.last||since}
    if(pts.length>1){if(!line)line=L.polyline(pts,{color:'#D1A955',weight:5,opacity:.9}).addTo(map);else line.setLatLngs(pts)}
    if(first){map.setView(ll,15);first=false}else if(!map.getBounds().pad(-0.2).contains(ll))map.panTo(ll);
    row.innerHTML=(d.ended?'':'<span class="spd">'+d.spd+' km/h</span> · ')+'actualizado '+ago(d.ts);
    if(d.ended)stopPolling();
  }catch(e){row.textContent='Sin conexión, reintentando…'}
}
function startPolling(){if(!timer){tick();timer=setInterval(tick,10000)}}
function stopPolling(){if(timer){clearInterval(timer);timer=null}}
document.addEventListener('visibilitychange',()=>{document.hidden?stopPolling():startPolling()});
startPolling();
</script></body></html>`;
  return new Response(html, {
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-robots-tag": "noindex" },
  });
}

function json(o, status = 200, headers = {}) {
  return new Response(JSON.stringify(o), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...headers },
  });
}

function randomId(n) {
  const abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(n));
  return [...bytes].map((b) => abc[b % abc.length]).join("");
}

async function sha(s) {
  const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(h)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
