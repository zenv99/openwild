"""Part-by-part facts from Wikipedia article text (English and Indonesian).

Wikipedia text is CC BY-SA; we extract facts only (which part is harmful or eaten, raw/cooked/unripe),
never copy sentences into the app, and keep a ledger of the article each fact came from.

A sentence counts as a HARM fact if it names a plant part and a harm word ("poisonous", "toxic",
"beracun"...). It counts as a FOOD fact if it names a part and an eating word ("edible", "eaten",
"dimakan"...) and has no negation ("not edible", "tidak dapat dimakan") and no harm word.
Safety (applied later in parts.py): harm always wins; food from Wikipedia alone is shown as
"food, with care", never as plain "reported food".
"""

from __future__ import annotations

import csv
import re
from pathlib import Path

from .http import get_json

LANGS = ("en", "id")

PART_WORDS = {
    "en": {
        "seeds": r"seeds?|kernels?|nuts?|beans?|pods?",
        "fruit": r"fruits?|berr(?:y|ies)|pulp",
        "flowers": r"flowers?|blossoms?|petals?|nectar|flower buds?",
        "leaves": r"leaf|leaves|foliage|fronds?",
        "stem": r"stems?|stalks?|shoots?|palm heart|canes?",
        "bark": r"bark",
        "roots": r"roots?|tubers?|bulbs?|rhizomes?|corms?",
        "sap": r"sap|latex|milky juice|resin",
    },
    "id": {
        "seeds": r"biji(?:nya)?|kacang|polong",
        "fruit": r"buah(?:nya)?|daging buah",
        "flowers": r"bunga(?:nya)?|kuncup",
        "leaves": r"daun(?:nya)?|pucuk",
        "stem": r"batang(?:nya)?|tunas|rebung|umbut",
        "bark": r"kulit batang|kulit kayu",
        "roots": r"akar(?:nya)?|umbi(?:nya)?|rimpang|bonggol",
        "sap": r"getah(?:nya)?|resin",
    },
}
HARM = {
    "en": r"poison|toxic|toxin|lethal|deadly|fatal|irritant|irritat|blister|dermatitis|cyanide|cyanogenic|"
          r"alkaloid|glycoside|saponin|oxalate|vomit|diarrh|convuls|caustic|harmful|purgative|emetic",
    "id": r"beracun|racun|toksik|mematikan|berbahaya|gatal|iritasi|melepuh|sianida|muntah|diare|mabuk|kejang",
}
FOOD = {
    "en": r"edible|eaten|are eaten|is eaten|cooked as|used as a vegetable|used in cooking|consumed|food|cuisine|"
          r"pickled|boiled|roasted|made into|delicacy",
    "id": r"dapat dimakan|bisa dimakan|dimakan|dikonsumsi|sayur|lalapan|direbus|digoreng|dimasak|diolah|makanan|rujak",
}
NEG = {
    "en": r"\bnot (?:be )?(?:edible|eaten|consumed)|inedible|never be eaten|should not be eaten|unpalatable|"
          r"caterpillar|larva|moth|butterfl|livestock|cattle|goats?\b|fodder|forage|birds|bats|monkeys|animals|insects",
    "id": r"tidak (?:dapat |bisa |boleh )?dimakan|tidak dikonsumsi|jangan dimakan|ternak|ulat|burung|kelelawar|monyet|hewan|pakan",
}
QUAL = {
    "en": (("raw", r"\braw\b|uncooked"), ("unripe", r"unripe|green fruit"), ("cooked", r"\bcooked\b|boil|roast")),
    "id": (("raw", r"mentah"), ("unripe", r"belum matang|buah muda|buah mentah"), ("cooked", r"dimasak|direbus|digoreng|dikukus")),
}
_ALL = {"en": r"all parts|whole plant|entire plant|every part", "id": r"seluruh bagian|semua bagian"}


def sentences(text: str) -> list[str]:
    return [s.strip() for s in re.split(r"(?<=[.!?])\s+|\n+", text or "") if 12 <= len(s.strip()) <= 600]


def facts_from_text(text: str, lang: str) -> list[tuple[str, str, str]]:
    """[(part, kind 'harm'|'food', qualifier)] from an article's plain text."""
    out = []
    parts_rx = {p: re.compile(rf"\b(?:{w})\b", re.I) for p, w in PART_WORDS[lang].items()}
    harm, food, neg = (re.compile(x[lang], re.I) for x in (HARM, FOOD, NEG))
    all_rx = re.compile(_ALL[lang], re.I)
    for s in sentences(text):
        named = list(PART_WORDS[lang]) if all_rx.search(s) else [p for p, rx in parts_rx.items() if rx.search(s)]
        if not named:
            continue
        qual = ""
        for q, rx in QUAL[lang]:
            if re.search(rx, s, re.I):
                qual = q
                break
        if harm.search(s):
            out += [(p, "harm", qual) for p in named]
        elif food.search(s) and not neg.search(s):
            out += [(p, "food", qual) for p in named]
    return out


def article_text(title: str, lang: str) -> tuple[str, str]:
    """Plain text of an article (following redirects) and its URL; ('', '') if none."""
    try:
        data = get_json(f"https://{lang}.wikipedia.org/w/api.php", {
            "action": "query", "titles": title, "redirects": "1", "prop": "extracts", "explaintext": "1",
            "format": "json",
        }, retries=2, timeout=30)
    except Exception:  # noqa: BLE001
        return "", ""
    for page in ((data.get("query") or {}).get("pages") or {}).values():
        if "missing" in page:
            continue
        t = page.get("title") or title
        return page.get("extract") or "", f"https://{lang}.wikipedia.org/wiki/{t.replace(' ', '_')}"
    return "", ""


def collect(names: list[str], out_csv: Path, workers: int = 4) -> int:
    """Fetch articles for every species name, extract part facts, write the ledger CSV. Returns rows written."""
    from concurrent.futures import ThreadPoolExecutor

    def one(name: str):
        rows = []
        for lang in LANGS:
            text, url = article_text(name, lang)
            if not text:
                continue
            # Only trust an article that is about this species (its name appears in the text).
            if name.split()[0].lower() not in text.lower():
                continue
            for part, kind, qual in sorted(set(facts_from_text(text, lang))):
                rows.append({"scientific_name": name, "part": part, "kind": kind, "qualifier": qual,
                             "lang": lang, "source_url": url})
        return rows

    out_csv.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "part", "kind", "qualifier", "lang", "source_url"])
        w.writeheader()
        with ThreadPoolExecutor(workers) as ex:
            for k, rows in enumerate(ex.map(one, names)):
                w.writerows(rows)
                n += len(rows)
                if k % 250 == 0:
                    print(f"wiki-parts {k}/{len(names)} species, {n} facts", flush=True)
    return n


def load(path: Path) -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {}
    if path.exists():
        for r in csv.DictReader(open(path, encoding="utf-8")):
            out.setdefault(r["scientific_name"].lower(), []).append(r)
    return out
