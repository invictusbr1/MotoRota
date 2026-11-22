"""Baixa os radares (highway=speed_camera) do OpenStreetMap para Brasil, Argentina,
Chile e Uruguai e grava em app/src/main/assets/radares.csv (lat,lon,limite,direcao).

Uso:  python scripts/atualizar_radares.py [arquivo_json_ja_baixado]
"""
import json, os, sys, urllib.parse, urllib.request

QUERY = ('[out:json][timeout:180];('
         'area["ISO3166-1"="BR"][admin_level=2];area["ISO3166-1"="AR"][admin_level=2];'
         'area["ISO3166-1"="CL"][admin_level=2];area["ISO3166-1"="UY"][admin_level=2];)->.a;'
         'node[highway=speed_camera](area.a);out body;')
SERVERS = ["https://overpass-api.de/api/interpreter",
           "https://overpass.private.coffee/api/interpreter"]
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "radares.csv")


def fetch():
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    for url in SERVERS:
        try:
            req = urllib.request.Request(url, data=body, headers={"User-Agent": "MotoRota-build/0.1"})
            with urllib.request.urlopen(req, timeout=240) as r:
                return json.load(r)
        except Exception as e:  # tenta o proximo servidor
            print("falhou", url, e, file=sys.stderr)
    raise SystemExit("nenhum servidor respondeu")


def speed(v):
    v = (v or "").strip().lower().replace("km/h", "").strip()
    return int(v) if v.isdigit() else 0


def direction(v):
    v = (v or "").strip()
    try:
        return str(int(float(v)) % 360)
    except ValueError:
        return ""


data = json.load(open(sys.argv[1], encoding="utf-8")) if len(sys.argv) > 1 else fetch()
rows = []
for e in data["elements"]:
    t = e.get("tags", {})
    rows.append(f'{e["lat"]:.6f},{e["lon"]:.6f},{speed(t.get("maxspeed"))},{direction(t.get("direction"))}')
rows.sort()
if len(rows) < 1000:
    raise SystemExit(f"poucos radares ({len(rows)}), mantendo o arquivo atual")
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("# Radares do OpenStreetMap (c) colaboradores OSM, ODbL. lat,lon,limite_kmh,direcao_graus\n")
    f.write("\n".join(rows) + "\n")
print(len(rows), "radares gravados em", OUT)
