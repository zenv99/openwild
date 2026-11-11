"""GBIF: which plant and fungus species occur in a region, how often, and what people call them."""
from __future__ import annotations

import concurrent.futures as cf
import csv
import json
from pathlib import Path

from . import config
from .http import get_json
from .regions import Region

# Languages kept for common names (English first, then the region's languages are preferred by the app).
NAME_LANGS = ["eng", "ind", "ban", "jav", "tgl", "tha", "vie", "msa", "spa", "por", "fra", "deu", "zho", "jpn", "hin"]


def species_counts(region: Region, kingdom: str, max_species: int) -> list[tuple[int, int]]:
    """Return [(gbif_species_key, occurrence_count)] sorted by count, using facet paging."""
    params = {
        **region.gbif,
        "taxonKey": config.GBIF_KINGDOM_KEYS[kingdom],
        "hasCoordinate": "true",
        "hasGeospatialIssue": "false",
        "occurrenceStatus": "PRESENT",
        "facet": "speciesKey",
        "limit": 0,
    }
    out: list[tuple[int, int]] = []
    page = 1000
    offset = 0
    while len(out) < max_species:
        data = get_json(
            f"{config.GBIF_API}/occurrence/search",
            {**params, "facetLimit": page, "facetOffset": offset},
        )
        facets = data.get("facets") or []
        counts = facets[0]["counts"] if facets else []
        if not counts:
            break
        out.extend((int(c["name"]), int(c["count"])) for c in counts)
        offset += page
        if len(counts) < page:
            break
    return out[:max_species]


def species_info(key: int) -> dict:
    d = get_json(f"{config.GBIF_API}/species/{key}")
    return {
        "gbif_key": key,
        "scientific_name": d.get("canonicalName") or d.get("scientificName"),
        "kingdom": d.get("kingdom"),
        "family": d.get("family"),
        "genus": d.get("genus"),
        "common_name": d.get("vernacularName"),
    }


def vernacular_names(key: int) -> dict[str, str]:
    """{language: first name} from GBIF's checklist sources."""
    try:
        d = get_json(f"{config.GBIF_API}/species/{key}/vernacularNames", {"limit": 200})
    except Exception:
        return {}
    names: dict[str, str] = {}
    for r in d.get("results", []):
        lang = (r.get("language") or "").lower()
        name = (r.get("vernacularName") or "").strip()
        if lang in NAME_LANGS and name and lang not in names:
            names[lang] = name[:80]
    return names


def _enrich(key_count: tuple[int, int], kingdom: str) -> dict | None:
    key, count = key_count
    try:
        info = species_info(key)
    except Exception:
        return None
    if not info["scientific_name"]:
        return None
    names = vernacular_names(key)
    info["kingdom"] = kingdom
    info["gbif_count"] = count
    info["common_name"] = names.get("eng") or info.get("common_name") or names.get("ind") or ""
    info["common_names"] = json.dumps(names, ensure_ascii=False) if names else ""
    return info


FIELDS = ["gbif_key", "scientific_name", "kingdom", "family", "genus", "common_name", "common_names", "gbif_count"]


def build_species_list(region: Region, out_csv: Path, workers: int = 8) -> Path:
    """Write every plant and fungus species recorded in the region (up to region.max_species each)."""
    rows: list[dict] = []
    for kingdom in ("Plantae", "Fungi"):
        keys = species_counts(region, kingdom, region.max_species)
        print(f"{region.id} {kingdom}: {len(keys)} species — fetching names")
        with cf.ThreadPoolExecutor(workers) as ex:
            for info in ex.map(lambda kc: _enrich(kc, kingdom), keys):
                if info:
                    rows.append(info)
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    return out_csv
