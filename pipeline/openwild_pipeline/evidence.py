"""Combine independent sources into one original line per plant, with a record of who said what.

Inputs (all facts, no copied prose):
  content/evidence/pfaf_facts.csv        Plants For A Future facts (parts, raw/cooked, hazard kinds, ratings)
  content/quick/auto_0_kew_useful.csv    Kew World Checklist of Useful Plant Species (food / medicine / poison / fuel)
  content/quick/auto_buckets.csv         Wikipedia + Wikimedia Commons categories via Wikidata
Outputs:
  content/quick/auto_combined.csv        one-liners in OpenWild's own words, with sources and an evidence count
  content/evidence/ledger.csv            every claim, per source, with a link: the audit trail

Rules (safety first):
  * any source saying "can kill"        -> deadly (or poisonous when a well-known food part exists)
  * strongly poisonous per any source   -> poisonous, never food
  * food + any poison/processing hazard -> "edible after cooking/preparation", with the hazard spelled out
  * "edible" (raw) only when PFAF records raw use and no source raises a hazard
  * a food claim from a single source is labelled as such
"""
from __future__ import annotations

import csv
from pathlib import Path

from .content import validate_quick

KEW_URL = "https://doi.org/10.5063/F1CV4G34"
WIKI_URL = "https://www.wikidata.org/"
SRC_NAME = {"pfaf": "PFAF", "kew": "Kew", "wiki": "Wikipedia/Commons"}

# Our own warning sentences, one per hazard kind.
HAZARD_TEXT = {
    "lethal": "Some parts can kill, even in small amounts.",
    "strong": "Strongly poisonous.",
    "poison": "Some parts are poisonous.",
    "cyanide": "Can release cyanide: peel, soak and cook thoroughly, never eat it raw.",
    "oxalate": "Raw plant contains oxalate crystals that burn the mouth and throat; cook thoroughly.",
    "thiaminase": "Raw plant destroys vitamin B1; cook before eating.",
    "saponin": "Contains soapy saponins; rinse and cook well.",
    "alkaloid": "Some parts contain alkaloids.",
    "heart": "Contains compounds that can affect the heart.",
    "purgative": "Can cause vomiting or diarrhoea.",
    "liver": "Repeated use can harm the liver.",
    "cancer": "Some compounds are linked to cancer with regular use.",
    "skin": "Sap or hairs can irritate the skin.",
    "sunlight": "Sap can make skin burn in sunlight.",
    "pregnancy": "Avoid during pregnancy.",
}
PROCESSING = {"poison", "cyanide", "oxalate", "thiaminase", "saponin", "heart", "purgative", "liver", "cancer"}
PREP_TEXT = [("leached", "Needs leaching (repeated soaking in water) before eating."),
             ("soaked", "Usually soaked before cooking.")]


def _rows(path: Path) -> dict[str, dict]:
    if not path.exists():
        return {}
    lines = [ln for ln in path.read_text(encoding="utf-8").splitlines() if ln and not ln.startswith("#")]
    return {r["scientific_name"].lower(): r for r in csv.DictReader(lines)}


def kew_claims(r: dict | None) -> dict:
    if not r:
        return {}
    v, b, h = r.get("verdict", ""), (r.get("benefit") or "").lower(), (r.get("harm") or "").lower()
    return {"food": v in ("edible", "edible_cooked") or "human food" in b, "poison": v == "toxic" or "poison" in h,
            "medicinal": v == "medicinal" or "medicinal" in b, "fire": "fuel" in b}


def wiki_claims(r: dict | None) -> dict:
    if not r:
        return {}
    v, b, h = r.get("verdict", ""), (r.get("benefit") or "").lower(), (r.get("harm") or "").lower()
    return {"food": v in ("edible", "edible_cooked") or "listed as" in b and v != "medicinal",
            "poison": v in ("toxic", "deadly") or "poisonous" in h, "deadly": v == "deadly",
            "psychoactive": v == "psychoactive",
            "medicinal": v == "medicinal" or "medic" in b or "ayurveda" in b, "cordage": "cordage" in (r.get("survival") or "")}


def _join(names: list[str]) -> str:
    return names[0] if len(names) == 1 else ", ".join(names[:-1]) + " and " + names[-1]


def combine(name: str, pf: dict | None, kew: dict | None, wiki: dict | None) -> tuple[dict | None, list[dict]]:
    """One original line + ledger entries for a plant."""
    k, w = kew_claims(kew), wiki_claims(wiki)
    tags = set(filter(None, (pf or {}).get("hazards", "").split(";")))
    parts = [p for p in (pf or {}).get("edible_parts", "").split(";") if p]
    prep = set(filter(None, (pf or {}).get("prep", "").split(";")))
    pf_food = bool(pf) and int(pf.get("edibility") or 0) >= 1
    pf_med = bool(pf) and int(pf.get("medicinal") or 0) >= 1
    pf_poison = bool(tags & ({"lethal", "strong"} | PROCESSING))

    food_src = [s for s, c in (("pfaf", pf_food), ("kew", k.get("food")), ("wiki", w.get("food"))) if c]
    # Kew's "poisons" category means USED AS a poison (fish/insect poison), not "dangerous to eat". It only
    # counts as a warning when nobody else records the plant as food, or another source also flags poison.
    kew_poison = bool(k.get("poison")) and (pf_poison or bool(w.get("poison")) or not (pf_food or w.get("food")))
    poison_src = [s for s, c in (("pfaf", pf_poison), ("kew", kew_poison), ("wiki", w.get("poison"))) if c]
    med_src = [s for s, c in (("pfaf", pf_med), ("kew", k.get("medicinal")), ("wiki", w.get("medicinal"))) if c]
    lethal = "lethal" in tags or w.get("deadly")
    strong = "strong" in tags

    ledger = []
    url = {"pfaf": (pf or {}).get("url", ""), "kew": KEW_URL, "wiki": WIKI_URL}
    for claim, srcs in (("food", food_src), ("poison", poison_src), ("medicine", med_src)):
        ledger += [{"scientific_name": name, "source": SRC_NAME[s], "claim": claim, "url": url[s]} for s in srcs]
    if k.get("poison") and not kew_poison:
        ledger.append({"scientific_name": name, "source": "Kew", "claim": "used-as-poison (not counted: food per other sources)", "url": KEW_URL})
    for t in sorted(tags):
        ledger.append({"scientific_name": name, "source": "PFAF", "claim": f"hazard:{t}", "url": url["pfaf"]})
    if not (food_src or poison_src or med_src or pf):
        return None, ledger

    survival = set(filter(None, (pf or {}).get("uses", "").split(";")))
    if k.get("fire"):
        survival.add("fire")
    if w.get("cordage"):
        survival.add("cordage")

    warn = [HAZARD_TEXT[t] for t in HAZARD_TEXT if t in tags and not (t == "poison" and tags & {"lethal", "strong"})]
    if "wiki" in poison_src:
        warn.append("Listed among poisonous plants on Wikipedia/Commons.")
    if "kew" in poison_src:
        warn.append("Kew records it as a poison plant.")
    harm = " ".join(warn)

    parts_txt = _join(parts) if parts else ""
    if lethal and (not food_src or w.get("deadly") or (pf and int(pf.get("edibility") or 0) <= 2)):
        verdict, benefit = "deadly", ""
    elif lethal or strong:
        verdict = "toxic"
        benefit = (f"Only the {parts_txt} {'is' if len(parts) == 1 else 'are'} eaten, by people who know this plant well. "
                   "Do not eat it in a survival situation.") if food_src and parts else ""
    elif w.get("psychoactive"):
        verdict, benefit = "psychoactive", ""
        harm = harm or "Mind-altering and poisonous in many cases; illegal in many countries."
    elif food_src and poison_src and (len(food_src) < 2 or "pfaf" not in food_src or not parts):
        # Poison flagged, and the food claim is thin (one source, or no documented edible parts): not food.
        verdict = "toxic"
        benefit = ""
        harm = harm or "Recorded as poisonous."
        harm += f" A food use is recorded by {_join([SRC_NAME[s] for s in food_src])}, but it is not safe to rely on."
    elif food_src:
        raw_ok = "raw" in prep and not (tags & PROCESSING) and not poison_src
        verdict = "edible" if raw_ok else "edible_cooked"
        bits = [f"Edible: {parts_txt}." if parts else "Used as food."]
        if "raw" in prep and raw_ok:
            bits.append("Eaten raw or cooked." if "cooked" in prep else "Eaten raw.")
        elif "cooked" in prep or verdict == "edible_cooked":
            bits.append("Eat it cooked.")
        bits += [t for p, t in PREP_TEXT if p in prep]
        if med_src:
            bits.append("Also used in traditional medicine.")
        n = len(food_src)
        bits.append(f"Food use confirmed by {n} sources ({_join([SRC_NAME[s] for s in food_src])})." if n > 1
                    else f"Food use recorded by one source ({SRC_NAME[food_src[0]]}); be extra careful.")
        benefit = " ".join(bits)
    elif poison_src:
        verdict, benefit = "toxic", ""
        harm = harm or "Recorded as poisonous."
    elif med_src:
        verdict = "medicinal"
        benefit = f"Used in traditional medicine ({_join([SRC_NAME[s] for s in med_src])}). Not a survival food."
    else:
        verdict, benefit = "inedible", "No food use recorded."

    uses = (["food"] if verdict in ("edible", "edible_cooked") else []) + \
           (["medicinal"] if med_src and verdict not in ("deadly", "toxic", "psychoactive") else []) + \
           sorted(survival & {"cordage", "fire", "shelter"})
    srcs = sorted({SRC_NAME[s] for s in food_src + poison_src + med_src} | ({"PFAF"} if pf else set()))
    row = {"scientific_name": name, "verdict": verdict, "benefit": benefit, "harm": harm, "survival": ";".join(uses),
           "source": "; ".join(srcs), "kingdom": "Plantae", "gbif_key": (wiki or {}).get("gbif_key", ""),
           "evidence": str(len(set(food_src + poison_src + med_src)))}
    return (row if not validate_quick(row) else None), ledger


def build(content_dir: Path) -> tuple[list[dict], list[dict]]:
    from .pfaf import read_facts

    pf = read_facts(content_dir / "evidence" / "pfaf_facts.csv")
    kew = _rows(content_dir / "quick" / "auto_0_kew_useful.csv")
    wiki = {k: r for k, r in _rows(content_dir / "quick" / "auto_buckets.csv").items() if r.get("kingdom") in ("Plantae", "")}
    rows, ledger = [], []
    for key in sorted(set(pf) | set(kew) | set(wiki)):
        name = (pf.get(key) or kew.get(key) or wiki.get(key))["scientific_name"]
        row, led = combine(name, pf.get(key), kew.get(key), wiki.get(key))
        ledger += led
        if row:
            rows.append(row)
    return rows, ledger


def write(rows: list[dict], ledger: list[dict], content_dir: Path) -> tuple[int, int]:
    q = content_dir / "quick" / "auto_combined.csv"
    with open(q, "w", newline="", encoding="utf-8") as f:
        f.write("# AUTO-GENERATED by `cli evidence`: one line per plant in OpenWild's own words, combining PFAF facts,\n")
        f.write("# Kew useful plants, and Wikipedia/Commons categories. Who said what: content/evidence/ledger.csv\n")
        w = csv.DictWriter(f, fieldnames=["scientific_name", "verdict", "benefit", "harm", "survival", "source",
                                          "kingdom", "gbif_key", "evidence"])
        w.writeheader()
        w.writerows(rows)
    led = content_dir / "evidence" / "ledger.csv"
    led.parent.mkdir(parents=True, exist_ok=True)
    with open(led, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "source", "claim", "url"])
        w.writeheader()
        w.writerows(ledger)
    return len(rows), len(ledger)
