#!/usr/bin/env python3
"""Build small per-area files with the SP95 (Gasolina 95 E5) price of every station in Spain.

Source: Ministerio para la Transición Ecológica (open data, updated every ~30 min):
  https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/
The whole list is ~10 MB, too much for a phone, so we cut it into 0.25° tiles (~25 km):
  <out>/fuel/<floor(lat*4)>_<floor(lon*4)>.json = {"t": "<source date>", "s": [[lat, lon, price, brand, address, town, hours], ...]}
The app downloads only the tiles around the rider.
"""
import json, math, os, sys, time, urllib.request

URL = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/"
out = os.path.join(sys.argv[1] if len(sys.argv) > 1 else "site", "fuel")


def num(s):
    try:
        return float(str(s).replace(",", "."))
    except Exception:
        return None


def fetch():
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(URL, headers={"Accept": "application/json", "User-Agent": "RideScreenAA-fuel/1.0 (+https://github.com/mgb1982/ridescreen-aa)"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except Exception as e:
            last = e
            print(f"fetch failed ({e}), retrying…", file=sys.stderr)
            time.sleep(20 * (attempt + 1))
    raise SystemExit(f"could not download prices: {last}")


data = fetch()
when = data.get("Fecha", "")
tiles = {}
n = 0
for s in data.get("ListaEESSPrecio", []):
    lat, lon, p = num(s.get("Latitud")), num(s.get("Longitud (WGS84)")), num(s.get("Precio Gasolina 95 E5"))
    if lat is None or lon is None or not p:
        continue
    key = f"{math.floor(lat * 4)}_{math.floor(lon * 4)}"
    tiles.setdefault(key, []).append([round(lat, 5), round(lon, 5), round(p, 3),
                                      (s.get("Rótulo") or "").strip()[:40], (s.get("Dirección") or "").strip()[:60],
                                      (s.get("Localidad") or "").strip().title()[:40], (s.get("Horario") or "").strip()[:60]])
    n += 1
if n < 1000:
    raise SystemExit(f"only {n} stations with SP95 — refusing to publish a broken set")
os.makedirs(out, exist_ok=True)
for key, st in tiles.items():
    with open(os.path.join(out, key + ".json"), "w") as f:
        json.dump({"t": when, "s": st}, f, ensure_ascii=False, separators=(",", ":"))
with open(os.path.join(out, "index.json"), "w") as f:
    json.dump({"t": when, "stations": n, "tiles": len(tiles), "built": int(time.time())}, f)
print(f"{n} stations with SP95 in {len(tiles)} tiles (source {when})")
