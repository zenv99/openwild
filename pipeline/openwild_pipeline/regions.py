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
    continent: str = ""
    wgsrpd: list[str] = field(default_factory=list)  # Kew/TDWG level-3 area codes for WCVP plant lists
    parent: str | None = None  # coarser pack this region sits inside (ID-BA -> ID). Never implies deletion.

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
        # YAML 1.1 turns unquoted NO/ON/YES into booleans (Norway!) — require strings.
        if not isinstance(r.id, str) or not all(isinstance(v, str) for v in r.gbif.values()):
            raise ValueError(f"Region id and gbif values must be strings (quote them): {r.id!r} {r.gbif!r}")
        for b in r.bbox:
            if len(b) != 4 or not (b[0] <= b[2] and b[1] <= b[3]):
                raise ValueError(f"Bad bbox for region {r.id}: {b}")
        if r.parent is not None and not isinstance(r.parent, str):
            raise ValueError(f"Region parent must be a string id: {r.id!r} {r.parent!r}")
    by_id = {r.id: r for r in regions}
    for r in regions:
        if not r.parent:
            continue
        if r.parent not in by_id:
            raise ValueError(f"Region {r.id} parent {r.parent} is not a region id")
        seen = {r.id}
        cur = by_id[r.parent]
        while cur.parent:
            if cur.id in seen:
                raise ValueError(f"Region parent cycle at {cur.id}")
            seen.add(cur.id)
            cur = by_id[cur.parent]
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


def country_of(regions: list[Region], region: Region) -> Region:
    """Walk parent links to the country (or world) pack. The finer region is kept."""
    by_id = {r.id: r for r in regions}
    seen: set[str] = set()
    cur = region
    while cur.parent and cur.id not in seen:
        seen.add(cur.id)
        nxt = by_id.get(cur.parent)
        if nxt is None:
            break
        cur = nxt
    return cur


def suggest(regions: list[Region], lon: float, lat: float) -> tuple[Region, Region | None] | None:
    """Country pack to offer first, plus a finer pack when the point sits inside one (Bali, a US state)."""
    finest = locate(regions, lon, lat)
    if finest is None:
        return None
    country = country_of(regions, finest)
    finer = finest if finest.id != country.id else None
    return country, finer


def names_not_in_parent(child: set[str], parent: set[str]) -> set[str]:
    """Scientific names a finer pack adds on top of its country pack. Same name is stored once."""
    have = {n.strip().lower() for n in parent}
    return {n for n in child if n.strip().lower() not in have}


def export_app_index(out: Path = config.ANDROID_ASSETS / "regions.json") -> Path:
    regions = load_regions()
    payload = {
        "schema": 2,
        "regions": [
            {"id": r.id, "name": r.name, "continent": r.continent, "bbox": r.bbox, "parent": r.parent}
            for r in regions
        ],
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


def region_from_pack(pack_path, region_id: str) -> int:
    """Write data/regions/<id>/species.csv, descriptions.csv and region.json from a published pack, so a
    region that was built elsewhere can go through the photo pipeline. Returns the species count."""
    import csv
    import json
    import sqlite3

    d = config.DATA_REGIONS / region_id
    d.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(pack_path)
    meta = dict(con.execute("SELECT key, value FROM meta"))
    rows = con.execute(
        "SELECT gbif_key, scientific_name, kingdom, family, genus, common_name, common_names, obs_count, description, wiki_url "
        "FROM species ORDER BY obs_count DESC"
    ).fetchall()
    with open(d / "species.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["gbif_key", "scientific_name", "kingdom", "family", "genus", "common_name", "common_names", "gbif_count", "source"])
        for r in rows:
            w.writerow([r[0] or "", r[1], r[2] or "Plantae", r[3] or "", r[4] or "", r[5] or "", r[6] or "", r[7] or 0, "pack"])
    with open(d / "descriptions.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["scientific_name", "description", "wiki_url", "lang"])
        for r in rows:
            if r[8]:
                w.writerow([r[1], r[8], r[9] or "", "en"])
    name = meta.get("region_name") or meta.get("name") or region_id
    (d / "region.json").write_text(json.dumps({"id": region_id, "name": name}), encoding="utf-8")
    return len(rows)


def region_or_pack_meta(region_id: str):
    """The region from regions.yaml, or (for packs built elsewhere) a stand-in with its id and name."""
    import json
    from types import SimpleNamespace

    try:
        return get_region(region_id)
    except KeyError:
        f = config.DATA_REGIONS / region_id / "region.json"
        info = json.loads(f.read_text(encoding="utf-8")) if f.exists() else {"id": region_id, "name": region_id}
        return SimpleNamespace(id=info["id"], name=info["name"])
