"""Part-by-part safety for each plant: seeds, fruit, flowers, leaves, stem, bark, roots, sap.

Sources, combined per part:
  * curated entries: ``if_eaten.harms.toxic_parts`` and ``if_eaten.benefits.edible_parts``
  * PFAF facts (facts only): which parts are eaten, raw/cooked, hazard kinds
  * one-liner and curated text: parts named in the harm (and, for food plants, benefit) sentences

Safety rules (a wrong "edible" can kill; a wrong "harmful" costs a meal):
  1. Harm wins. A part named as harmful anywhere is harmful, even if another source says it is eaten.
  2. "All parts" / "whole plant" in a harm source marks every part harmful.
  3. On a deadly plant no part is ever shown as food; parts with no harm record say "treat as deadly".
  4. On a poisonous plant, a part reported as eaten is shown as "experts only", never as plain edible.
  5. Food reports from only one source with a poison hazard on the plant are downgraded the same way.
  6. Parts with no information are not listed; the app says "Other parts: no information. Don't eat."
"""

from __future__ import annotations

import re

PARTS = ["seeds", "fruit", "flowers", "leaves", "stem", "bark", "roots", "sap"]

# Words that name each part (singular/plural and common variants).
_WORDS = {
    "seeds": r"seeds?|kernels?|nuts?|beans?|pits?|seed ?pods?|pods?",
    "fruit": r"fruits?|berr(?:y|ies)|fruit pulp|pulp",
    "flowers": r"flowers?|blossoms?|petals?|nectar|pollen|buds?",
    "leaves": r"leaf|leaves|foliage|fronds?",
    "stem": r"stems?|stalks?|shoots?|palm heart|canes?|apical bud",
    "bark": r"(?:inner )?bark",
    "roots": r"roots?|tubers?|bulbs?|rhizomes?|corms?",
    "sap": r"sap|latex|milky juice|resin|gum|manna",
}
_PART_RE = {p: re.compile(rf"\b(?:{w})\b", re.I) for p, w in _WORDS.items()}
_ALL = re.compile(r"\b(?:all parts|whole plant|entire plant|every part|all of the plant)\b", re.I)
_QUALIFIER = re.compile(r"\b(raw|unripe|green|young|old|in large amounts|when raw|uncooked|dried)\b", re.I)

# PFAF edible-part tokens -> our parts.
_PFAF = {
    "seed": "seeds", "seedpod": "seeds", "oil": "seeds", "fruit": "fruit", "flowers": "flowers", "nectar": "flowers",
    "pollen": "flowers", "leaves": "leaves", "shoots": "stem", "stem": "stem", "apical bud": "stem",
    "inner bark": "bark", "root": "roots", "sap": "sap", "manna": "sap",
}


def parts_in(text: str | None) -> dict[str, str]:
    """Parts named in a text -> the qualifier in the same clause ('raw', 'unripe', ...) or ''.
    Qualifiers are read per clause so 'raw leaves irritate; seeds are deadly' doesn't soften the seeds."""
    if not text:
        return {}
    out: dict[str, str] = {}
    for clause in re.split(r"[;.,—]|\band\b(?=\s+(?:the\s+)?\w+\s+(?:are|is|can|cause))", str(text)):
        q = _QUALIFIER.search(clause)
        qual = q.group(1).lower() if q else ""
        named = PARTS if _ALL.search(clause) else [p for p, rx in _PART_RE.items() if rx.search(clause)]
        for p in named:
            # An unqualified mention anywhere beats a qualified one (harm without conditions).
            if p not in out or (out[p] and not qual):
                out[p] = qual
    return out


RAW_ONLY = {"raw", "when raw", "uncooked"}
UNRIPE_ONLY = {"unripe", "green"}


def build_parts(content: dict | None, pfaf: dict | None, is_fungus: bool, wiki: list[dict] | None = None) -> list[dict]:
    """Rows for the parts we know something about: {part, status, note}.
    status: deadly | toxic | harmful | experts_only | edible_cooked | edible | caution."""
    if is_fungus:
        return []
    content = content or {}
    tox = (content.get("toxicity") or "unknown").lower()
    edib = (content.get("edibility") or "unknown").lower()
    deadly = tox == "deadly" or edib == "deadly"
    poisonous = deadly or tox == "serious" or edib == "toxic"
    ie = content.get("if_eaten") or {}
    harms = ie.get("harms") or {}
    benefits = ie.get("benefits") or {}

    harmful: dict[str, str] = {}
    for phrase in harms.get("toxic_parts") or []:
        for p, q in parts_in(phrase).items():
            harmful.setdefault(p, q)
    for t in (harms.get("symptoms"), harms.get("severity")):
        for p, q in parts_in(t).items():
            harmful.setdefault(p, q)
    wiki_food: dict[str, str] = {}
    wiki_harm: dict[str, str] = {}
    for r in wiki or []:
        q = r.get("qualifier") or ""
        if r.get("kind") == "harm":
            if r["part"] not in wiki_harm or (wiki_harm[r["part"]] and not q):
                wiki_harm[r["part"]] = q if q in ("raw", "unripe") else ""
        elif r.get("kind") == "food":
            wiki_food.setdefault(r["part"], q)
    pf_haz = set((pfaf or {}).get("hazards", "").split(";")) - {""}

    edible: dict[str, str] = {}
    for phrase in benefits.get("edible_parts") or []:
        for p, q in parts_in(phrase).items():
            edible.setdefault(p, q)
    if edib.startswith("edible"):
        for p, q in parts_in(benefits.get("nutrition")).items():
            edible.setdefault(p, q)
    pf_parts = set()
    if pfaf and str(pfaf.get("edibility") or "0") not in ("", "0"):
        for tok in (pfaf.get("edible_parts") or "").split(";"):
            if tok in _PFAF:
                pf_parts.add(_PFAF[tok])
    prep = set((pfaf or {}).get("prep", "").split(";")) - {""}
    cooked_only = bool(prep) and "raw" not in prep or edib == "edible_with_preparation"
    plant_hazard = bool(pf_haz & {"poison", "lethal", "cyanide", "strong", "alkaloid", "saponin", "thiaminase", "oxalate"})

    # Wikipedia harm reports fill in parts the curated notes don't cover. Where our notes are more precise
    # ("poisonous raw", "unripe"), they win; on a part the notes list as food, a general Wikipedia warning
    # becomes "with care" rather than overruling the food report.
    wiki_caution: set[str] = set()
    for p, q in wiki_harm.items():
        if p in harmful:
            continue
        if (p in edible or p in pf_parts) and not deadly and not (tox == "serious" or edib == "toxic"):
            wiki_caution.add(p)
        else:
            harmful[p] = q

    # Staples like cassava and taro are poisonous raw but eaten everywhere once properly cooked.
    food_plant = edib.startswith("edible") and not deadly
    rows = []
    for p in PARTS:
        reported = p in edible or p in pf_parts
        if not reported and p in wiki_food and p not in harmful:
            # Mentioned as eaten in Wikipedia only: never plain "edible".
            if deadly:
                rows.append({"part": p, "status": "deadly", "note": "Treat as deadly. Some sources mention eating it; don't."})
            elif poisonous:
                rows.append({"part": p, "status": "experts_only",
                             "note": "Mentioned as eaten, but the plant is poisonous. Don't eat."})
            else:
                note = "Mentioned as eaten in one source; not verified."
                if wiki_food[p] == "cooked":
                    note = "Mentioned as eaten cooked in one source; not verified."
                rows.append({"part": p, "status": "caution", "note": note})
            continue
        if p in wiki_caution:
            rows.append({"part": p, "status": "caution",
                         "note": "Eaten, but one source also reports harm. Prepare properly; avoid if unsure."})
            continue
        if p in harmful:
            q = harmful[p]
            if (food_plant or not poisonous) and not deadly and reported and q in RAW_ONLY:
                rows.append({"part": p, "status": "edible_cooked", "note": "Cooked only. Poisonous raw."})
                continue
            if (food_plant or not poisonous) and not deadly and reported and q in UNRIPE_ONLY:
                rows.append({"part": p, "status": "caution", "note": "Ripe only. Poisonous unripe."})
                continue
            if (food_plant or not poisonous) and not deadly and reported and q == "in large amounts":
                rows.append({"part": p, "status": "caution", "note": "Reported eaten. Harmful in larger amounts."})
                continue
            if deadly:
                rows.append({"part": p, "status": "deadly", "note": _note(q, "Can kill.")})
            elif poisonous:
                rows.append({"part": p, "status": "toxic", "note": _note(q, "Poisonous.")})
            else:
                rows.append({"part": p, "status": "harmful", "note": _note(q, "Can irritate or make you sick.")})
            continue
        if deadly:
            if reported:
                # Never food on a deadly plant, even if some source lists it.
                rows.append({"part": p, "status": "deadly", "note": "Treat as deadly. Some sources mention eating it; don't."})
            continue
        if not reported:
            continue
        if poisonous and not food_plant:
            rows.append({"part": p, "status": "experts_only",
                         "note": "Reported eaten after special preparation, but the plant is poisonous. Don't eat."})
        elif plant_hazard and p not in edible:
            rows.append({"part": p, "status": "experts_only",
                         "note": "Reported eaten, but sources also list hazards for this plant. Not without expert advice."})
        elif cooked_only:
            rows.append({"part": p, "status": "edible_cooked", "note": _prep_note(prep, edible.get(p, ""))})
        elif plant_hazard or tox == "mild":
            rows.append({"part": p, "status": "caution", "note": "Reported eaten. This plant also has warnings: read them first."})
        else:
            rows.append({"part": p, "status": "edible", "note": _prep_note(prep, edible.get(p, ""))})
    if deadly and not rows:
        rows = [{"part": "all", "status": "deadly", "note": "Treat every part as deadly."}]
    return rows


def _note(qual: str, base: str) -> str:
    return {"raw": f"{base} Especially raw.", "unripe": f"{base} Especially unripe.", "when raw": f"{base} Especially raw.",
            "in large amounts": f"{base} In larger amounts.", "green": f"{base} Especially green/unripe."}.get(qual, base)


def _prep_note(prep: set[str], qual: str) -> str:
    bits = []
    if qual == "young":
        bits.append("Young parts.")
    if "raw" in prep and "cooked" in prep:
        bits.append("Reported eaten raw or cooked.")
    elif "cooked" in prep:
        bits.append("Cooked only.")
    elif "raw" in prep:
        bits.append("Reported eaten raw.")
    for extra, txt in (("soaked", "Some uses need soaking."), ("leached", "Some uses need leaching."),
                       ("fermented", "Some uses are fermented.")):
        if extra in prep:
            bits.append(txt)
    return " ".join(bits) or "Reported eaten."


def load_pfaf(path) -> dict[str, dict]:
    import csv

    lines = [ln for ln in open(path, encoding="utf-8") if not ln.startswith("#")]
    return {r["scientific_name"].lower(): r for r in csv.DictReader(lines)}


def add_parts_to_pack(pack_path, pfaf: dict[str, dict], version: int, wiki: dict[str, list[dict]] | None = None) -> int:
    """Add the part-by-part table to every species' content in an existing pack, and bump its version.
    Returns the number of species that got at least one part row."""
    import json
    import sqlite3

    con = sqlite3.connect(pack_path)
    n = 0
    rows = con.execute(
        "SELECT c.species_id, c.json, s.scientific_name, s.kingdom FROM content c JOIN species s ON s.id = c.species_id"
    ).fetchall()
    for sid, js, name, kingdom in rows:
        try:
            content = json.loads(js) if js else {}
        except json.JSONDecodeError:
            continue
        if not isinstance(content, dict):
            continue
        content["parts"] = build_parts(content, pfaf.get((name or "").lower()), kingdom == "Fungi",
                                       (wiki or {}).get((name or "").lower()))
        n += bool(content["parts"])
        con.execute("UPDATE content SET json = ? WHERE species_id = ?", (json.dumps(content), sid))
    con.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('pack_version', ?)", (str(version),))
    con.commit()
    con.execute("VACUUM")
    con.close()
    return n
