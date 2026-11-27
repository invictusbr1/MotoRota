"""Diagnostico da Serra das Araras: o que o mapa offline diz e qual rota o motor faz."""
import json, os, sys, urllib.parse, urllib.request

BOX = (-22.78, -43.95, -22.60, -43.70)  # S, W, N, E (Serra das Araras / Pirai / Paracambi)

def inbox(lat, lon):
    return BOX[0] <= lat <= BOX[2] and BOX[1] <= lon <= BOX[3]

route = json.load(open(sys.argv[1]))
props = route["features"][0]["properties"]
coords = route["features"][0]["geometry"]["coordinates"]
print("== ROTA DO MOTOR (mapa offline) ==")
print("distancia total km:", int(props["track-length"]) / 1000)
msgs = props["messages"]
hdr = msgs[0]
print("colunas:", hdr)
li, la, wt = hdr.index("Longitude"), hdr.index("Latitude"), hdr.index("WayTags")
print("-- trechos dentro da Serra --")
for m in msgs[1:]:
    lon, lat = int(m[li]) / 1e6, int(m[la]) / 1e6
    if inbox(lat, lon):
        print(f"{lat:.5f},{lon:.5f}  {m[wt]}")

print("\n== OSM: vias em obra / restritas na Serra ==")
q = (f'[out:json][timeout:90];(way[highway=construction]({BOX[0]},{BOX[1]},{BOX[2]},{BOX[3]});'
     f'way[highway~"motorway|trunk"]({BOX[0]},{BOX[1]},{BOX[2]},{BOX[3]}););out tags center;')
for url in ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"]:
    try:
        req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": q}).encode(),
                                     headers={"User-Agent": "MotoRota-diag"})
        data = json.load(urllib.request.urlopen(req, timeout=120))
        for el in data["elements"]:
            t = el.get("tags", {})
            c = el.get("center", {})
            flag = "OBRA" if t.get("highway") == "construction" else ""
            interesting = {k: v for k, v in t.items() if k in ("highway", "construction", "name", "ref", "oneway", "access", "motor_vehicle", "motorcar", "lanes", "opening_date", "check_date")}
            print(f"{flag:5} way {el['id']} {c.get('lat', 0):.5f},{c.get('lon', 0):.5f} {interesting}")
        break
    except Exception as e:
        print("overpass falhou", url, e)

key = os.environ.get("TOMTOM_KEY", "")
if key:
    print("\n== TOMTOM no mesmo trecho ==")
    inside = [(lat, lon) for lon, lat, *_ in coords if inbox(lat, lon)]
    if inside:
        a, b = inside[0], inside[-1]
        url = (f"https://api.tomtom.com/routing/1/calculateRoute/{a[0]},{a[1]}:{b[0]},{b[1]}/json?key={key}"
               "&travelMode=car&routeType=fastest&traffic=true")
        r = json.load(urllib.request.urlopen(url, timeout=30))
        s = r["routes"][0]["summary"]
        along = 0.0
        import math
        def d(p, q):
            R = 6371000; x = math.radians(q[1]-p[1]) * math.cos(math.radians((p[0]+q[0])/2)); y = math.radians(q[0]-p[0])
            return R * math.hypot(x, y)
        for i in range(1, len(inside)): along += d(inside[i-1], inside[i])
        print(f"motor offline dentro da caixa: {along/1000:.1f} km | TomTom entre os mesmos pontos: {s['lengthInMeters']/1000:.1f} km")
