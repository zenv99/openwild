"""Every name people use for a plant ("also called"), so a search for the local name finds it.

Sources: GBIF vernacular names (all of them per language, not just the first) and Wikidata labels and aliases
in every language, matched by scientific name (P225). Includes the region's own tongues, e.g. Balinese (ban),
Javanese (jav), Sundanese (sun), Indonesian (ind), Malay (msa), Tagalog (tgl), Thai (tha), Vietnamese (vie).

Stored in the pack's species.common_names as {lang (ISO 639-3): "name; other name; ..."}.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from . import config
from .http import get_json

WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"
# Wikidata language codes (mostly ISO 639-1) -> the 3-letter codes the packs use.
LANG3 = {
    "en": "eng", "id": "ind", "ban": "ban", "jv": "jav", "su": "sun", "ms": "msa", "tl": "tgl", "th": "tha",
    "vi": "vie", "km": "khm", "lo": "lao", "my": "mya", "zh": "zho", "ja": "jpn", "ko": "kor", "hi": "hin",
    "bn": "ben", "ta": "tam", "ne": "nep", "si": "sin", "es": "spa", "pt": "por", "fr": "fra", "de": "deu",
    "nl": "nld", "it": "ita", "ru": "rus", "ar": "ara", "fa": "fas", "tr": "tur", "sw": "swa", "mi": "mri",
    "haw": "haw", "fj": "fij", "tet": "tet", "ceb": "ceb", "mad": "mad", "min": "min", "ace": "ace",
}
MAX_PER_LANG = 5


def _clean(name: str) -> str:
    return re.sub(r"\s+", " ", name).strip(" ;,")[:80]


def wikidata_names(scientific: list[str]) -> dict[str, dict[str, list[str]]]:
    """{scientific name (lower): {lang3: [names]}} from Wikidata labels + aliases, 120 names per query."""
    out: dict[str, dict[str, list[str]]] = {}
    langs = ",".join(f'"{k}"' for k in LANG3)
    for i in range(0, len(scientific), 120):
        chunk = [s for s in scientific[i:i + 120] if '"' not in s and "\\" not in s]
        values = " ".join(f'"{s}"' for s in chunk)
        q = f"""
        SELECT ?name ?label WHERE {{
          VALUES ?name {{ {values} }}
          ?item wdt:P225 ?name .
          {{ ?item rdfs:label ?label }} UNION {{ ?item skos:altLabel ?label }}
          FILTER(LANG(?label) IN ({langs}))
        }}"""
        try:
            d = get_json(WIKIDATA_SPARQL, {"query": q, "format": "json"}, retries=4, timeout=90)
        except Exception as e:  # noqa: BLE001
            print(f"NAMES wikidata batch {i} failed: {e!r}", flush=True)
            continue
        for b in (d.get("results") or {}).get("bindings") or []:
            sci = b["name"]["value"].lower()
            lab = b["label"]
            lang = LANG3.get(lab.get("xml:lang", ""))
            name = _clean(lab["value"])
            if not lang or not name or name.lower() == sci:
                continue
            lst = out.setdefault(sci, {}).setdefault(lang, [])
            if name.lower() not in (x.lower() for x in lst) and len(lst) < MAX_PER_LANG:
                lst.append(name)
    return out


def merge(existing: dict[str, str], extra: dict[str, list[str]]) -> dict[str, str]:
    """Merge name lists per language ("a; b; c"), no duplicates, at most MAX_PER_LANG each."""
    merged: dict[str, list[str]] = {}
    for lang, v in (existing or {}).items():
        merged[lang] = [x for x in (_clean(p) for p in re.split(r"[;]", str(v))) if x]
    for lang, names in (extra or {}).items():
        lst = merged.setdefault(lang, [])
        for n in names:
            if n.lower() not in (x.lower() for x in lst) and len(lst) < MAX_PER_LANG:
                lst.append(n)
    return {k: "; ".join(v) for k, v in merged.items() if v}


def region_names(region_id: str, scientific: list[str]) -> dict[str, dict[str, list[str]]]:
    """Wikidata names for a region's plants, cached in content/evidence/names/<region>.json."""
    path = config.REPO_ROOT / "content" / "evidence" / "names" / f"{region_id}.json"
    cached: dict = {}
    if path.exists():
        try:
            cached = json.loads(path.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            cached = {}
    todo = [s for s in scientific if s.lower() not in cached]
    if todo:
        got = wikidata_names(todo)
        for s in todo:
            cached[s.lower()] = got.get(s.lower(), {})
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(cached, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    n = sum(1 for v in cached.values() if v)
    print(f"NAMES {region_id}: {n} of {len(cached)} plants have names on Wikidata", flush=True)
    return cached


def best_common_name(names: dict[str, str], prefer: list[str]) -> str | None:
    for lang in prefer + ["eng"]:
        v = names.get(lang)
        if v:
            return v.split(";")[0].strip()
    return None


def local_languages(region_id: str) -> list[str]:
    """The region's own languages first, for display order and for a fallback common name."""
    country = region_id.split("-")[0]
    table = {
        "ID": ["ind", "ban", "jav", "sun", "mad", "min"], "MY": ["msa"], "BN": ["msa"], "SG": ["msa", "zho"],
        "PH": ["tgl", "ceb"], "TL": ["tet", "ind"], "TH": ["tha"], "VN": ["vie"], "KH": ["khm"], "LA": ["lao"],
        "MM": ["mya"], "CN": ["zho"], "TW": ["zho"], "JP": ["jpn"], "KR": ["kor"], "IN": ["hin", "tam", "ben"],
        "NP": ["nep"], "LK": ["sin", "tam"], "BD": ["ben"], "NZ": ["mri"], "FJ": ["fij"], "US": [],
    }
    langs = table.get(country, [])
    if region_id == "ID-BA":
        langs = ["ban", "ind", "jav"]
    return langs
