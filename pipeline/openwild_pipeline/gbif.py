"""GBIF: which plant and fungus species occur in a region, and how often."""
from __future__ import annotations

import csv
from pathlib import Path

from . import config
from .http import get_json
from .regions import Region


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


def build_species_list(region: Region, out_csv: Path) -> Path:
    """Write the region's species list: plants and fungi split by the region's species cap."""
    cap_fungi = max(200, region.max_species // 5)
    cap_plants = region.max_species - cap_fungi
    rows = []
    for kingdom, cap in (("Plantae", cap_plants), ("Fungi", cap_fungi)):
        for key, count in species_counts(region, kingdom, cap):
            info = species_info(key)
            if not info["scientific_name"]:
                continue
            info["kingdom"] = kingdom
            info["gbif_count"] = count
            rows.append(info)
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(out_csv, "w", newline="") as f:
        w = csv.DictWriter(
            f,
            fieldnames=["gbif_key", "scientific_name", "kingdom", "family", "genus", "common_name", "gbif_count"],
        )
        w.writeheader()
        w.writerows(rows)
    return out_csv
