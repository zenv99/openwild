"""Cultivated and introduced species that a regional occurrence list does not already contain.

Houseplants, garden ornamentals and fungi grown or traded in a country are often absent from the
wild GBIF species list. `data/regions/<id>/cultivated.csv` records those names with the citation
that supports adding them. A later pack build unions the rows into the species list. It does not
replace a name the occurrence list already has.
"""
from __future__ import annotations

import csv
from pathlib import Path

from . import config

FIELDS = [
    "gbif_key", "scientific_name", "kingdom", "family", "genus", "common_name", "common_names",
    "gbif_count", "basis", "citation",
]


def cultivated_path(region_id: str) -> Path:
    return config.DATA_REGIONS / region_id / "cultivated.csv"


def cultivated_species_rows(region_id: str) -> list[dict]:
    """Rows shaped like `species.csv`, so pack builds can union them. Basis and citation are kept."""
    path = cultivated_path(region_id)
    if not path.exists():
        return []
    out = []
    for row in csv.DictReader(open(path, encoding="utf-8")):
        name = (row.get("scientific_name") or "").strip()
        if not name:
            continue
        out.append({
            "gbif_key": (row.get("gbif_key") or "").strip(),
            "scientific_name": name,
            "kingdom": (row.get("kingdom") or "Plantae").strip() or "Plantae",
            "family": (row.get("family") or "").strip(),
            "genus": (row.get("genus") or "").strip(),
            "common_name": (row.get("common_name") or "").strip(),
            "common_names": (row.get("common_names") or "").strip(),
            "gbif_count": (row.get("gbif_count") or "0").strip() or "0",
            "basis": (row.get("basis") or "").strip(),
            "citation": (row.get("citation") or "").strip(),
            "source": "cultivated",
        })
    return out
