"""Preço médio da gasolina por estado (ANP, levantamento semanal) -> dados/combustivel.json"""
import io, json, re, sys, urllib.request
import openpyxl

PAGE = "https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/levantamento-de-precos-de-combustiveis-ultimas-semanas-pesquisadas"
UA = {"User-Agent": "Mozilla/5.0"}
UF = {
    "ACRE": "AC", "ALAGOAS": "AL", "AMAPA": "AP", "AMAZONAS": "AM", "BAHIA": "BA", "CEARA": "CE", "DISTRITO FEDERAL": "DF",
    "ESPIRITO SANTO": "ES", "GOIAS": "GO", "MARANHAO": "MA", "MATO GROSSO": "MT", "MATO GROSSO DO SUL": "MS",
    "MINAS GERAIS": "MG", "PARA": "PA", "PARAIBA": "PB", "PARANA": "PR", "PERNAMBUCO": "PE", "PIAUI": "PI",
    "RIO DE JANEIRO": "RJ", "RIO GRANDE DO NORTE": "RN", "RIO GRANDE DO SUL": "RS", "RONDONIA": "RO", "RORAIMA": "RR",
    "SANTA CATARINA": "SC", "SAO PAULO": "SP", "SERGIPE": "SE", "TOCANTINS": "TO",
}
PROD = {"GASOLINA COMUM": "comum", "GASOLINA ADITIVADA": "aditivada", "ETANOL HIDRATADO": "etanol"}

html = urllib.request.urlopen(urllib.request.Request(PAGE, headers=UA), timeout=60).read().decode("utf-8", "ignore")
links = re.findall(r'https://[^"]*resumo_semanal_lpc[^"]*\.xlsx', html)
if not links:
    sys.exit("nenhuma planilha encontrada")
url = links[0]
data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read()
ws = openpyxl.load_workbook(io.BytesIO(data), read_only=True, data_only=True)["ESTADOS"]
out = {"fonte": "ANP - Levantamento de Preços de Combustíveis (semanal)", "estados": {}, "brasil": {}}
tot = {}
header = None
for row in ws.iter_rows(values_only=True):
    if row and row[0] == "DATA INICIAL":
        header = list(row)
        continue
    if not header or not row or row[0] is None:
        continue
    r = dict(zip(header, row))
    prod = PROD.get(str(r.get("PRODUTO", "")).strip())
    uf = UF.get(str(r.get("ESTADOS", "")).strip())
    preco = r.get("PREÇO MÉDIO REVENDA")
    n = r.get("NÚMERO DE POSTOS PESQUISADOS") or 0
    if not prod or not uf or not isinstance(preco, (int, float)):
        continue
    out["estados"].setdefault(uf, {})[prod] = round(float(preco), 2)
    t = tot.setdefault(prod, [0.0, 0])
    t[0] += float(preco) * n; t[1] += n
    out["semana"] = f"{r['DATA INICIAL']:%d/%m} a {r['DATA FINAL']:%d/%m/%Y}"
for prod, (s, n) in tot.items():
    if n:
        out["brasil"][prod] = round(s / n, 2)
if len(out["estados"]) < 20:
    sys.exit("poucos estados lidos: %d" % len(out["estados"]))
json.dump(out, open("dados/combustivel.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(out["semana"], out["brasil"], "RJ", out["estados"].get("RJ"))
