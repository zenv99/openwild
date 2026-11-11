"""Big open datasets that multiply coverage.

* WCVP — World Checklist of Vascular Plants (Royal Botanic Gardens, Kew; CC BY 4.0):
  every accepted vascular plant species (~343,000) with the WGSRPD areas it grows in.
  Gives complete regional plant lists even where GBIF has few records.
* Wikidata (CC0): property P789 "edibility" for mushrooms -> automatic one-liners.
"""
from __future__ import annotations

import csv
import io
import zipfile
from pathlib import Path

from . import config
from .http import download, get_json

WCVP_URL = "http://sftp.kew.org/pub/data-repositories/WCVP/wcvp.zip"
WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"


# ---------------------------------------------------------------- WCVP
def _open_member(z: zipfile.ZipFile, stem: str):
    name = next(n for n in z.namelist() if Path(n).name.startswith(stem) and n.endswith(".csv"))
    text = io.TextIOWrapper(z.open(name), encoding="utf-8", errors="replace")
    head = text.readline()
    delim = "|" if head.count("|") > head.count(",") else ","
    return csv.DictReader(text, fieldnames=[h.strip() for h in head.strip().split(delim)], delimiter=delim)


def wcvp_species(areas: list[str], native_only: bool = False) -> list[dict]:
    """Accepted species recorded in any of the WGSRPD level-3 area codes."""
    zpath = config.WORK / "wcvp.zip"
    if not zpath.exists():
        download(WCVP_URL, zpath)
    areas_set = {a.upper() for a in areas}
    with zipfile.ZipFile(zpath) as z:
        ids: dict[str, bool] = {}
        for r in _open_member(z, "wcvp_distribution"):
            if (r.get("area_code_l3") or "").upper() in areas_set and r.get("extinct", "0") in ("0", ""):
                introduced = r.get("introduced", "0") == "1"
                if native_only and introduced:
                    continue
                ids[r["plant_name_id"]] = ids.get(r["plant_name_id"], True) and introduced
        out = []
        for r in _open_member(z, "wcvp_names"):
            pid = r.get("plant_name_id")
            if pid in ids and r.get("taxon_rank") == "Species" and r.get("taxon_status") == "Accepted":
                out.append({
                    "scientific_name": (r.get("taxon_name") or "").strip(),
                    "family": r.get("family", ""),
                    "genus": r.get("genus", ""),
                    "introduced": ids[pid],
                })
    return out


def merge_wcvp_into_species_csv(species_csv: Path, wcvp_rows: list[dict]) -> int:
    """Append WCVP species not already in the region list (gbif_count 0). Returns number added."""
    with open(species_csv, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fields = list(reader.fieldnames or [])
        rows = list(reader)
    if "source" not in fields:
        fields.append("source")
        for r in rows:
            r["source"] = r.get("source") or "gbif"
    have = {r["scientific_name"].lower() for r in rows}
    added = 0
    for w in wcvp_rows:
        if w["scientific_name"] and w["scientific_name"].lower() not in have:
            rows.append({"scientific_name": w["scientific_name"], "kingdom": "Plantae", "family": w["family"],
                         "genus": w["genus"], "gbif_count": 0, "source": "wcvp"})
            have.add(w["scientific_name"].lower())
            added += 1
    with open(species_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    return added


# ---------------------------------------------------------------- Wikidata
EDIBILITY_QUERY = """
SELECT ?name ?edLabel WHERE {
  ?taxon wdt:P789 ?ed ; wdt:P225 ?name ; wdt:P105 wd:Q7432 .
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
}
"""

# Most dangerous wins when a species has several values.
_RANK = {"deadly": 5, "toxic": 4, "psychoactive": 3, "inedible": 2, "edible_cooked": 1, "edible": 0}


def _verdict(label: str) -> str | None:
    l = label.lower()
    if "deadly" in l or "lethal" in l:
        return "deadly"
    if "poison" in l or "toxic" in l:
        return "toxic"
    if "psychoactive" in l or "hallucinogen" in l:
        return "psychoactive"
    if "caution" in l or "not recommended" in l or "inedible" in l or "unknown" in l:
        return "inedible"
    if "cooked" in l:
        return "edible_cooked"
    if "edible" in l or "choice" in l:
        return "edible"
    return None


def wikidata_fungi_edibility(out_csv: Path) -> int:
    d = get_json(WIKIDATA_SPARQL, {"query": EDIBILITY_QUERY, "format": "json"}, cache=False)
    best: dict[str, str] = {}
    for b in d["results"]["bindings"]:
        name = b["name"]["value"].strip()
        v = _verdict(b.get("edLabel", {}).get("value", ""))
        if not name or not v:
            continue
        if name not in best or _RANK[v] > _RANK[best[name]]:
            best[name] = v
    lines = {
        "edible": ("Listed as edible on Wikidata — mushroom ID must be certain", ""),
        "edible_cooked": ("Listed as edible when cooked on Wikidata", "Raw or undercooked can make you ill"),
        "inedible": ("", ""),
        "toxic": ("", "Listed as poisonous on Wikidata"),
        "deadly": ("", "Listed as deadly poisonous on Wikidata"),
        "psychoactive": ("", "Listed as psychoactive on Wikidata — toxic lookalikes exist"),
    }
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        f.write("# AUTO-GENERATED from Wikidata P789 (CC0). Do not edit; manual content/quick files override these rows.\n")
        w = csv.writer(f)
        w.writerow(["scientific_name", "verdict", "benefit", "harm", "survival", "source"])
        for name in sorted(best):
            v = best[name]
            benefit, harm = lines[v]
            w.writerow([name, v, benefit, harm, "food" if v.startswith("edible") else "", "Wikidata P789"])
    return len(best)
