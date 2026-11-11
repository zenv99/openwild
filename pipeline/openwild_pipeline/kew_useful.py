"""Kew's World Checklist of Useful Plant Species (Diazgranados et al. 2020, CC BY 4.0).

40,292 species with documented human uses in ten categories, compiled from 13 datasets including
PROSEA (Plant Resources of South-East Asia). We use four flags: Human food, Medicines, Poisons, Fuels.

Mapping (conservative; we know the use, not which part or how it is prepared):
  Human food only           -> edible_cooked ("recorded as human food; cook before eating")
  Human food + Poisons      -> edible_cooked with a poison warning
  Poisons (no food)         -> toxic
  Medicines (no food/poison)-> medicinal
  only animal food / environmental / gene source -> skipped (says nothing about eating)

Written to content/quick/auto_0_kew_useful.csv, which loads FIRST: the Wikipedia buckets and Wikidata
mushroom data override it, and hand-written files override everything.
"""
from __future__ import annotations

import csv
import io
import re
import urllib.parse
import urllib.request
from pathlib import Path

from .http import USER_AGENT, get_json

KNB = "https://knb.ecoinformatics.org/knb/d1/mn/v2"
TITLE = "World Checklist of Useful Plant Species"


def _solr(q: str, fl: str, rows: int = 50) -> list[dict]:
    d = get_json(f"{KNB}/query/solr/", {"q": q, "fl": fl, "wt": "json", "rows": rows}, cache=False)
    return d.get("response", {}).get("docs", [])


def find_data_files() -> list[dict]:
    """Every data object that looks like part of the checklist (any version), largest first."""
    seen, files = set(), []
    metas = _solr(f'title:"{TITLE}"', "identifier,documents,obsoletedBy,dateUploaded", rows=20)
    print("KNB metadata records:", [(m.get("identifier"), m.get("obsoletedBy"), len(m.get("documents") or [])) for m in metas])
    pids = [pid for m in metas for pid in (m.get("documents") or [])]
    for q in ('fileName:*seful*', 'fileName:*WCUPS*', 'fileName:*hecklist_of_Useful*'):
        try:
            pids += [d["identifier"] for d in _solr(q, "identifier", rows=50)]
        except Exception as e:  # noqa: BLE001
            print(f"solr {q}: {e!r}")
    for pid in pids:
        if pid in seen:
            continue
        seen.add(pid)
        got = _solr(f'identifier:"{pid}"', "identifier,fileName,formatId,size,obsoletedBy", rows=1)
        if got:
            files.append(got[0])
    files.sort(key=lambda f: -int(float(f.get("size") or 0)))
    return files


def pdf_rows(data: bytes) -> list[dict]:
    """Fallback: the checklist PDF, via `pdftotext -layout`. Prints a sample so the layout can be checked."""
    import subprocess
    import tempfile

    with tempfile.TemporaryDirectory() as d:
        pdf, txt = Path(d) / "c.pdf", Path(d) / "c.txt"
        pdf.write_bytes(data)
        subprocess.run(["pdftotext", "-layout", str(pdf), str(txt)], check=True)
        lines = txt.read_text(errors="replace").splitlines()
    print(f"PDF text: {len(lines)} lines")
    sample = [ln for ln in lines if re.match(r"^\s*[A-Z][a-z]+ [a-z-]+", ln)]
    for ln in lines[300:340] + sample[2000:2040]:
        print("PDFLINE|" + ln[:220])
    return parse_pdf_lines(lines)


def parse_pdf_lines(lines: list[str]) -> list[dict]:
    """Placeholder until the layout is confirmed: returns nothing."""
    return []


def download_object(pid: str) -> bytes:
    url = f"{KNB}/object/{urllib.parse.quote(pid, safe='')}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def read_table(name: str, data: bytes) -> list[dict]:
    n = (name or "").lower()
    if n.endswith((".xlsx", ".xls")):
        import pandas as pd

        return pd.read_excel(io.BytesIO(data)).fillna("").astype(str).to_dict("records")
    text = data.decode("utf-8-sig", errors="replace")
    dialect = csv.Sniffer().sniff(text[:5000], delimiters=",;\t")
    return list(csv.DictReader(io.StringIO(text), dialect=dialect))


def _col(cols: list[str], *patterns: str) -> str | None:
    for p in patterns:
        for c in cols:
            if re.fullmatch(p, c.strip(), flags=re.I):
                return c
    for p in patterns:
        for c in cols:
            if re.search(p, c, flags=re.I):
                return c
    return None


def _flag(v) -> bool:
    s = str(v).strip().lower()
    return s not in ("", "0", "0.0", "false", "no", "nan", "none", "-")


def to_rows(table: list[dict]) -> list[dict]:
    if not table:
        return []
    cols = list(table[0].keys())
    name_c = _col(cols, r"binomial_acc_name", r"binomial.*", r"accepted.?name", r"species.?name", r"taxon.?name", r"scientific.?name")
    genus_c, sp_c = _col(cols, r"genus"), _col(cols, r"species", r"specific.?epithet")
    food_c = _col(cols, r"human.?food", r"humanfood")
    med_c = _col(cols, r"medicines?", r"medicinal")
    poi_c = _col(cols, r"poisons?")
    fuel_c = _col(cols, r"fuels?")
    print(f"columns: name={name_c} genus={genus_c} species={sp_c} food={food_c} med={med_c} poison={poi_c} fuel={fuel_c}")
    print(f"all columns: {cols}")
    out: dict[str, dict] = {}
    for r in table:
        if name_c:
            name = " ".join(str(r.get(name_c, "")).split()[:2])
        else:
            name = f"{r.get(genus_c, '')} {r.get(sp_c, '')}".strip()
        if not re.fullmatch(r"[A-Z][a-z-]+ [a-z-]+", name or ""):
            continue
        food = bool(food_c and _flag(r.get(food_c)))
        med = bool(med_c and _flag(r.get(med_c)))
        poison = bool(poi_c and _flag(r.get(poi_c)))
        fuel = bool(fuel_c and _flag(r.get(fuel_c)))
        uses = (["food"] if food else []) + (["medicinal"] if med else []) + (["fire"] if fuel else [])
        if food and poison:
            v, benefit = "edible_cooked", "Recorded as human food (Kew useful plants)"
            harm = "Also recorded as a poison: some parts or raw material are toxic. Eat only parts known to be edible, properly prepared."
        elif food:
            v, benefit, harm = "edible_cooked", "Recorded as human food (Kew useful plants)", ""
        elif poison:
            v, benefit, harm = "toxic", "", "Recorded as a poison (Kew useful plants)."
            uses = [u for u in uses if u == "fire"]
        elif med:
            v, benefit, harm = "medicinal", "Recorded as a medicinal plant (Kew useful plants)", ""
        else:
            continue
        if med and food:
            benefit += "; also medicinal"
        if fuel and v != "toxic":
            benefit += "; used as fuel"
        out[name.lower()] = {"scientific_name": name, "verdict": v, "benefit": benefit, "harm": harm,
                             "survival": ";".join(uses), "source": "Kew World Checklist of Useful Plant Species (CC BY 4.0)",
                             "kingdom": "Plantae", "gbif_key": ""}
    return sorted(out.values(), key=lambda r: r["scientific_name"])


def write(rows: list[dict], out_csv: Path) -> int:
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        f.write("# AUTO-GENERATED from Kew's World Checklist of Useful Plant Species (Diazgranados et al. 2020, CC BY 4.0).\n")
        f.write("# Loads first: Wikipedia buckets, Wikidata and hand-written files override these rows.\n")
        w = csv.DictWriter(f, fieldnames=["scientific_name", "verdict", "benefit", "harm", "survival", "source", "kingdom", "gbif_key"])
        w.writeheader()
        w.writerows(rows)
    return len(rows)


def run(out_csv: Path) -> int:
    files = find_data_files()
    print("KNB files:", [(f.get("fileName"), f.get("formatId"), f.get("size"), f.get("obsoletedBy")) for f in files])
    tables = [f for f in files if str(f.get("fileName", "")).lower().endswith((".csv", ".xlsx", ".xls", ".txt", ".tsv"))]
    for f in tables:
        rows = to_rows(read_table(f["fileName"], download_object(f["identifier"])))
        if len(rows) > 1000:
            return write(rows, out_csv)
        print(f"{f['fileName']}: only {len(rows)} usable rows, trying next file")
    pdfs = [f for f in files if str(f.get("fileName", "")).lower().endswith(".pdf")]
    for f in pdfs[:1]:
        rows = pdf_rows(download_object(f["identifier"]))
        if len(rows) > 1000:
            return write(rows, out_csv)
    raise RuntimeError("No usable table found in the KNB package")
