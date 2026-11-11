"""Region definitions: load regions.yaml, export the app's offline locator index."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from . import config


@dataclass
class Region:
    id: str
    name: str
    gbif: dict = field(default_factory=dict)
    bbox: list[list[float]] = field(default_factory=list)
    max_species: int = 5000

    def contains(self, lon: float, lat: float) -> bool:
        return any(b[0] <= lon <= b[2] and b[1] <= lat <= b[3] for b in self.bbox)

    @property
    def area(self) -> float:
        return sum((b[2] - b[0]) * (b[3] - b[1]) for b in self.bbox)


def load_regions(path: Path = config.REGIONS_YAML) -> list[Region]:
    data = yaml.safe_load(Path(path).read_text())
    regions = [Region(**r) for r in data["regions"]]
    ids = [r.id for r in regions]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate region ids in regions.yaml")
    for r in regions:
        for b in r.bbox:
            if len(b) != 4 or not (b[0] <= b[2] and b[1] <= b[3]):
                raise ValueError(f"Bad bbox for region {r.id}: {b}")
    return regions


def get_region(region_id: str) -> Region:
    for r in load_regions():
        if r.id == region_id:
            return r
    raise KeyError(f"Unknown region {region_id!r}")


def locate(regions: list[Region], lon: float, lat: float) -> Region | None:
    """Same rule as the app: smallest region whose boxes contain the point."""
    hits = [r for r in regions if r.contains(lon, lat)]
    return min(hits, key=lambda r: r.area) if hits else None


def export_app_index(out: Path = config.ANDROID_ASSETS / "regions.json") -> Path:
    regions = load_regions()
    payload = {
        "schema": 1,
        "regions": [{"id": r.id, "name": r.name, "bbox": r.bbox} for r in regions],
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=1))
    return out


def regions_from_naturalearth(geojson_path: Path, max_species: int = 6000) -> str:
    """Generate regions.yaml entries (one per country) from a Natural Earth admin-0 GeoJSON.

    Download (free, public domain): https://www.naturalearthdata.com/downloads/ -> Admin 0 Countries.
    Each country's bbox is computed per polygon part so islands and exclaves get their own boxes.
    """
    gj = json.loads(Path(geojson_path).read_text())
    out = []
    for feat in gj["features"]:
        props = feat["properties"]
        iso = props.get("ISO_A2_EH") or props.get("ISO_A2")
        if not iso or iso == "-99":
            continue
        geom = feat["geometry"]
        polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
        boxes = []
        for poly in polys:
            ring = poly[0]
            lons = [p[0] for p in ring]
            lats = [p[1] for p in ring]
            box = [round(min(lons), 2), round(min(lats), 2), round(max(lons), 2), round(max(lats), 2)]
            if (box[2] - box[0]) * (box[3] - box[1]) > 0.01:  # skip tiny islets
                boxes.append(box)
        if boxes:
            out.append(
                {
                    "id": iso,
                    "name": props.get("NAME_EN") or props.get("NAME"),
                    "gbif": {"country": iso},
                    "bbox": boxes,
                    "max_species": max_species,
                }
            )
    return yaml.safe_dump({"regions": out}, sort_keys=False, allow_unicode=True)
