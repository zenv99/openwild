"""Plants For A Future (pfaf.org) database -> FACTS only.

Used with the permission of Plants For A Future. We do not copy PFAF's descriptions, selection or
wording: from each record we keep only checkable facts (which parts are eaten, whether raw or cooked,
which kinds of hazard are mentioned, food/medicine ratings, survival uses, the PFAF page) and write
them to content/evidence/pfaf_facts.csv. The one-liners shown in the app are written by
evidence.py in our own words, combining these facts with Kew, Wikipedia/Commons and Wikidata.
"""
from __future__ import annotations

import csv
import re
import sqlite3
from pathlib import Path

CITE = re.compile(r"\s*\[\s*[\dA-Z ,;\-]*\]")
BINOMIAL = re.compile(r"\b([A-Z][a-z-]+) ([a-z][a-z-]+)\b")
NONE_KNOWN = re.compile(r"^\s*(none known|none recorded|no known)\b", re.I)

# Hazard kinds (facts), detected from the hazard notes. The app's warning sentences are ours (evidence.py).
HAZARDS = {
    "lethal": r"\b(fatal\w*|lethal|deaths?|deadly|kill\w*)\b",
    "strong": r"\b(very|highly|extremely|violently|seriously)\s+(poisonous|toxic)\b|\ball parts\b.{0,60}\b(poisonous|toxic)\b|\bwhole plant\b.{0,30}\b(poisonous|toxic)\b",
    "poison": r"\b(poison\w*|toxic\w*|toxin\w*|harmful)\b",
    "cyanide": r"\b(cyan\w*|hydrocyanic|prussic|amygdalin|linamarin)\b",
    "oxalate": r"\boxalat\w*",
    "alkaloid": r"\balkaloid\w*",
    "saponin": r"\bsaponin\w*",
    "heart": r"\b(cardiac|heart|cardenolide\w*|digitalis)\b",
    "skin": r"\b(skin|dermatitis|rash\w*|blister\w*)\b",
    "purgative": r"\b(purgative|laxative|emetic|vomit\w*|diarrh\w*)\b",
    "sunlight": r"\b(photosensiti\w*|phototox\w*)\b",
    "cancer": r"\bcarcinogen\w*",
    "liver": r"\b(liver|hepato\w*|pyrrolizidine)\b",
    "pregnancy": r"\b(pregnan\w*|abortifacient)\b",
    "thiaminase": r"\bthiaminase\b",
}
HAZARD_RE = {k: re.compile(v, re.I) for k, v in HAZARDS.items()}
PREP = {
    "raw": r"\braw\b",
    "cooked": r"\b(cooked|boiled|roasted|baked|fried|steamed)\b",
    "dried": r"\bdried\b",
    "leached": r"\bleach\w*",
    "soaked": r"\bsoak\w*",
    "fermented": r"\bferment\w*",
}
PREP_RE = {k: re.compile(v, re.I) for k, v in PREP.items()}
CORDAGE = {"Fibre", "String", "Basketry", "Weaving", "Cordage"}
FIRE = {"Fuel", "Charcoal", "Tinder"}
SHELTER = {"Thatching", "Wood", "Fencing"}
FIELDS = ["scientific_name", "listed_as", "edibility", "medicinal", "edible_parts", "prep", "hazards", "uses", "url"]


def hazard_tags(text: str | None) -> list[str]:
    t = CITE.sub("", text or "").strip()
    if not t or NONE_KNOWN.match(t):
        return []
    return [k for k, rx in HAZARD_RE.items() if rx.search(t)]


def prep_tags(text: str | None) -> list[str]:
    return [k for k, rx in PREP_RE.items() if rx.search(text or "")]


def synonyms(field: str | None, genus: str) -> list[str]:
    """'Hibiscus esculentus. L.' / 'A. pectinata. A. picea.' -> binomials (abbreviated genus expanded)."""
    if not field:
        return []
    f = re.sub(r"\b[A-Z]\.\s+(?=[a-z])", genus + " ", field)
    return sorted({f"{g} {e}" for g, e in BINOMIAL.findall(f) if e not in ("var", "subsp", "ssp", "f", "et", "ex")})


def facts(db: Path) -> list[dict]:
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    uses: dict[str, dict[str, set]] = {}
    for plant, cat, nm in con.execute("SELECT plant, category, name FROM plant_uses"):
        uses.setdefault(plant, {}).setdefault(cat, set()).add(nm)
    out: dict[str, dict] = {}
    syn: dict[str, dict] = {}
    for p in con.execute("SELECT * FROM plants"):
        p = dict(p)
        name = " ".join((p["latin_name"] or "").split()[:2])
        if not BINOMIAL.fullmatch(name):
            continue
        u = uses.get(p["latin_name"], {})
        other = u.get("other uses", set())
        surv = (["cordage"] if other & CORDAGE else []) + (["fire"] if other & FIRE else []) + (["shelter"] if other & SHELTER else [])
        row = {
            "scientific_name": name, "listed_as": "",
            "edibility": int(p.get("edibility_rating") or 0), "medicinal": int(p.get("medicinal_rating") or 0),
            "edible_parts": ";".join(sorted(x.lower() for x in u.get("edible parts", set()))),
            "prep": ";".join(prep_tags(p.get("edible_uses"))),
            "hazards": ";".join(hazard_tags(p.get("known_hazards"))),
            "uses": ";".join(surv),
            "url": p.get("source_url") or f"https://pfaf.org/user/Plant.aspx?LatinName={name.replace(' ', '+')}",
        }
        out[name.lower()] = row
        for s in synonyms(p.get("synonyms"), name.split()[0]):
            if s.lower() != name.lower():
                syn.setdefault(s.lower(), {**row, "scientific_name": s, "listed_as": name})
    for k, r in syn.items():  # accepted names win over synonyms
        out.setdefault(k, r)
    con.close()
    return sorted(out.values(), key=lambda r: r["scientific_name"])


def write_facts(rows: list[dict], out_csv: Path) -> int:
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        f.write("# Facts extracted from the Plants For A Future database (pfaf.org), used with permission.\n")
        f.write("# No PFAF text: parts eaten, raw/cooked, hazard kinds, ratings, survival uses, source page.\n")
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)
    return len(rows)


def read_facts(path: Path) -> dict[str, dict]:
    if not path.exists():
        return {}
    lines = [ln for ln in path.read_text(encoding="utf-8").splitlines() if ln and not ln.startswith("#")]
    return {r["scientific_name"].lower(): r for r in csv.DictReader(lines)}
