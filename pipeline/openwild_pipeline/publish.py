"""Region pack downloads (Organic Maps style).

Packs are published as assets of the GitHub release "packs", next to an index.json the app reads:
  {"packs": [{"region_id", "name", "version", "url", "sha256", "size", "species_count",
              "photo_species", "has_photo_id", "model_id"}]}
The app only downloads from GitHub over HTTPS and checks the SHA-256 before installing.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
from pathlib import Path


def pack_entry(pack: Path, url: str) -> dict:
    con = sqlite3.connect(pack)
    meta = dict(con.execute("SELECT key, value FROM meta"))
    species = con.execute("SELECT COUNT(*) FROM species").fetchone()[0]
    tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    photo_species = con.execute("SELECT COUNT(DISTINCT species_id) FROM photo").fetchone()[0] if "photo" in tables else 0
    protos = con.execute("SELECT COUNT(*) FROM prototype").fetchone()[0] if "prototype" in tables else 0
    con.close()
    h = hashlib.sha256()
    with open(pack, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return {
        "region_id": meta["region_id"], "name": meta.get("region_name", meta["region_id"]),
        "version": int(meta["pack_version"]), "url": url, "sha256": h.hexdigest(), "size": pack.stat().st_size,
        "species_count": species, "photo_species": photo_species, "has_photo_id": protos > 0,
        "model_id": meta.get("model_id", ""),
    }


def update_index(index_path: Path, entry: dict) -> dict:
    idx = {"packs": []}
    if index_path.exists() and index_path.stat().st_size:
        try:
            idx = json.loads(index_path.read_text())
        except json.JSONDecodeError:
            pass
    packs = [p for p in idx.get("packs", []) if p.get("region_id") != entry["region_id"]]
    packs.append(entry)
    idx = {"format": 1, "packs": sorted(packs, key=lambda p: p["region_id"])}
    index_path.write_text(json.dumps(idx, indent=1))
    return idx
