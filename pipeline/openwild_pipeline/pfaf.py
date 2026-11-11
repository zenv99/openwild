"""Plants For A Future (pfaf.org) database -> one-line verdicts.

Used with the permission of Plants For A Future (Ken Fern). We publish only short derived lines
(verdict, edible parts, key preparation note, hazards), each credited to PFAF; the database itself is
not redistributed.

Rules (safety first):
  edibility rating >= 1, hazards mention poison/toxins -> edible_cooked, hazard shown
  edibility rating >= 1, eaten raw                       -> edible
  edibility rating >= 1, otherwise                       -> edible_cooked
  edibility 0, hazards say fatal/deadly                  -> deadly
  edibility 0, hazards mention poison/toxins             -> toxic
  edibility 0, medicinal rating >= 1                     -> medicinal
  edibility 0, no hazards, no medicine                   -> inedible ("no known food use")
A plant that Wikipedia/Commons/Kew list as poisonous keeps that warning even when PFAF rates it edible.
Synonyms in PFAF become extra rows (same content) so more pack species match.
"""
from __future__ import annotations

import csv
import re
import sqlite3
from pathlib import Path

from .content import AMOUNT, DOSE_WORDS, validate_quick

CITE = re.compile(r"\s*\[\s*[\dA-Z ,;\-]*\]")
DEADLY_RE = re.compile(r"\b(fatal\w*|deadly|death|lethal|kill\w*)\b", re.I)
TOXIC_RE = re.compile(r"\b(poison\w*|toxic\w*|toxin\w*|harmful|cyanogen\w*|hydrocyanic|alkaloid\w*|saponin\w*|"
                      r"oxalate\w*|glycoside\w*|glucoside\w*|carcinogen\w*|hepatotox\w*)\b", re.I)
# Words strong enough to say "only eat known parts, properly prepared" even for a well-rated food plant.
DEMOTE_RE = re.compile(r"\b(poison\w*|toxic\w*|toxin\w*|harmful|cyanogen\w*|hydrocyanic|glucoside\w*|glycoside\w*|"
                       r"oxalate\w*|carcinogen\w*|hepatotox\w*)\b", re.I)
STRONG_RE = re.compile(r"\b(very|highly|extremely|violently|deadly|seriously)\s+(poisonous|toxic)\b|"
                       r"\ball parts\b.{0,60}\b(poisonous|toxic)\b|\bwhole plant\b.{0,30}\b(poisonous|toxic)\b", re.I)
NONE_KNOWN = re.compile(r"^\s*(none known|none recorded|no known)\b", re.I)
BINOMIAL = re.compile(r"\b([A-Z][a-z-]+) ([a-z][a-z-]+)\b")
CORDAGE = {"Fibre", "String", "Basketry", "Weaving", "Cordage"}
FIRE = {"Fuel", "Charcoal", "Tinder"}
SHELTER = {"Thatching", "Wood", "Fencing"}
SOURCE = "Plants For A Future, pfaf.org (used with permission)"


def clean(text: str | None, limit: int = 220) -> str:
    """Drop citation marks, keep whole sentences without dose/amount wording, up to `limit` chars."""
    if not text:
        return ""
    t = re.sub(r"\s+", " ", CITE.sub("", text)).strip()
    out = ""
    for s in re.split(r"(?<=[.!?])\s+", t):
        s = s.strip()
        if not s or DOSE_WORDS.search(s) or AMOUNT.search(s):
            continue
        if len(out) + len(s) + 1 > limit:
            break
        out = f"{out} {s}".strip()
    return out


def synonyms(field: str | None, genus: str) -> list[str]:
    """'Hibiscus esculentus. L.' / 'A. pectinata. A. picea.' -> binomials (abbreviated genus expanded)."""
    if not field:
        return []
    f = re.sub(r"\b[A-Z]\.\s+(?=[a-z])", genus + " ", field)
    return sorted({f"{g} {e}" for g, e in BINOMIAL.findall(f) if e not in ("var", "subsp", "ssp", "f", "et", "ex")})


def to_row(p: dict, uses: dict[str, set]) -> dict | None:
    name = " ".join((p["latin_name"] or "").split()[:2])
    if not BINOMIAL.fullmatch(name):
        return None
    edible = int(p.get("edibility_rating") or 0)
    medicinal = int(p.get("medicinal_rating") or 0)
    full_hz = CITE.sub("", p.get("known_hazards") or "")
    hz_raw = clean(p.get("known_hazards"), 260)
    hz = "" if NONE_KNOWN.match(hz_raw) else hz_raw
    if NONE_KNOWN.match(full_hz.strip()):
        full_hz = ""
    parts = sorted(uses.get("edible parts", set()))
    other = uses.get("other uses", set())
    eu = clean(p.get("edible_uses"), 200)

    survival = []
    if edible:
        survival.append("food")
    if medicinal >= 2:
        survival.append("medicinal")
    if other & CORDAGE:
        survival.append("cordage")
    if other & FIRE:
        survival.append("fire")
    if other & SHELTER:
        survival.append("shelter")

    if edible and full_hz and (DEADLY_RE.search(full_hz) or STRONG_RE.search(full_hz)):
        # Some part can kill or is strongly poisonous (castor bean, yew, physic nut): never present it as food.
        verdict = "deadly" if edible <= 2 and DEADLY_RE.search(full_hz) else "toxic"
        what = ", ".join(p.lower() for p in parts) or "some parts"
        benefit = "" if verdict == "deadly" else f"Only the {what} is eaten, by people who know it well. Do not eat it in a survival situation."
        harm = hz
        survival = [u for u in survival if u in ("cordage", "fire", "shelter")]
    elif edible:
        what = f"Edible ({', '.join(p.lower() for p in parts)})" if parts else "Edible"
        benefit = f"{what}, PFAF rating {edible}/5. {eu}".strip()
        if hz and DEMOTE_RE.search(hz):
            verdict = "edible_cooked"
        elif re.search(r"\braw\b", p.get("edible_uses") or "", re.I):
            verdict = "edible"
        else:
            verdict = "edible_cooked"
        harm = hz
    elif full_hz and DEADLY_RE.search(full_hz):
        verdict, benefit, harm = "deadly", "", hz or clean(full_hz, 260)
        survival = [u for u in survival if u in ("cordage", "fire", "shelter")]
    elif full_hz and TOXIC_RE.search(full_hz):
        verdict, benefit, harm = "toxic", "", hz or clean(full_hz, 260)
        survival = [u for u in survival if u in ("cordage", "fire", "shelter")]
    elif medicinal:
        verdict, harm = "medicinal", hz
        benefit = f"Medicinal plant, PFAF rating {medicinal}/5. {clean(p.get('medicinal_uses'), 160)}".strip()
    else:
        verdict, benefit, harm = "inedible", "No known food use (PFAF).", hz
    row = {"scientific_name": name, "verdict": verdict, "benefit": benefit, "harm": harm,
           "survival": ";".join(survival), "source": SOURCE, "kingdom": "Plantae", "gbif_key": ""}
    return row if not validate_quick(row) else None


def merge_danger(row: dict, earlier: dict | None) -> dict:
    """Keep a poison warning from Wikipedia/Commons/Kew when PFAF rates the plant edible."""
    if not earlier or earlier.get("verdict") not in ("toxic", "deadly") or row["verdict"] not in ("edible", "edible_cooked"):
        return row
    extra = earlier.get("harm") or "Listed as poisonous elsewhere."
    harm = f"{row['harm']} {extra}".strip() if extra not in (row["harm"] or "") else row["harm"]
    return {**row, "verdict": "edible_cooked", "harm": harm}


def build(db: Path, earlier: dict[str, dict] | None = None) -> list[dict]:
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    uses: dict[str, dict[str, set]] = {}
    for plant, cat, nm in con.execute("SELECT plant, category, name FROM plant_uses"):
        uses.setdefault(plant, {}).setdefault(cat, set()).add(nm)
    rows: dict[str, dict] = {}
    syn_rows: dict[str, dict] = {}
    for p in con.execute("SELECT * FROM plants"):
        p = dict(p)
        r = to_row(p, uses.get(p["latin_name"], {}))
        if not r:
            continue
        r = merge_danger(r, (earlier or {}).get(r["scientific_name"].lower()))
        rows[r["scientific_name"].lower()] = r
        for s in synonyms(p.get("synonyms"), r["scientific_name"].split()[0]):
            if s.lower() != r["scientific_name"].lower():
                syn_rows.setdefault(s.lower(), {**r, "scientific_name": s,
                                                "benefit": (r["benefit"] + f" (PFAF lists it as {p['latin_name']}.)").strip()})
    for k, r in syn_rows.items():  # accepted names always win over synonyms
        if k not in rows:
            r = merge_danger(r, (earlier or {}).get(k))
            if not validate_quick(r):
                rows[k] = r
    con.close()
    return sorted(rows.values(), key=lambda r: r["scientific_name"])


def write(rows: list[dict], out_csv: Path) -> int:
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        f.write("# AUTO-GENERATED from the Plants For A Future database (pfaf.org), used with permission.\n")
        f.write("# Overrides Kew and Wikipedia rows; hand-written files override these. Poison warnings from other sources are kept.\n")
        w = csv.DictWriter(f, fieldnames=["scientific_name", "verdict", "benefit", "harm", "survival", "source", "kingdom", "gbif_key"])
        w.writeheader()
        w.writerows(rows)
    return len(rows)
