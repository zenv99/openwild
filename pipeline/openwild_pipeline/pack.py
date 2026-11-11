"""Build a region pack (SQLite) and maintain the hosted index.json. Format: docs/REGION_PACK_FORMAT.md."""
from __future__ import annotations

import hashlib
import json
import sqlite3
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from . import config
from .content import finalize, load_group_rules, load_quick, resolve
from .prototypes import build_species_prototypes, quantize

SCHEMA_VERSION = 1

SCHEMA_SQL = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE species (
  id INTEGER PRIMARY KEY, inat_taxon_id INTEGER, gbif_key INTEGER,
  scientific_name TEXT NOT NULL, common_name TEXT, common_names TEXT,
  kingdom TEXT NOT NULL, family TEXT, genus TEXT,
  obs_count INTEGER NOT NULL DEFAULT 0, months BLOB, description TEXT, wiki_url TEXT
);
CREATE TABLE prototype (
  id INTEGER PRIMARY KEY, species_id INTEGER NOT NULL REFERENCES species(id),
  vec BLOB NOT NULL, scale REAL NOT NULL, kind TEXT NOT NULL DEFAULT 'image'
);
CREATE INDEX prototype_species ON prototype(species_id);
CREATE TABLE content (species_id INTEGER PRIMARY KEY REFERENCES species(id), json TEXT NOT NULL);
CREATE TABLE lookalike (
  species_id INTEGER NOT NULL REFERENCES species(id), lookalike_species_id INTEGER,
  lookalike_name TEXT NOT NULL, danger TEXT NOT NULL
);
CREATE TABLE projection (id INTEGER PRIMARY KEY CHECK (id = 1), rows INTEGER NOT NULL, cols INTEGER NOT NULL,
  matrix BLOB NOT NULL, mean BLOB NOT NULL);
CREATE TABLE attribution (
  id INTEGER PRIMARY KEY, species_id INTEGER NOT NULL, observer TEXT, license TEXT NOT NULL, url TEXT NOT NULL
);
"""


@dataclass
class SpeciesRecord:
    scientific_name: str
    kingdom: str
    inat_taxon_id: int | None = None
    gbif_key: int | None = None
    common_name: str | None = None
    common_names: dict = field(default_factory=dict)
    family: str | None = None
    genus: str | None = None
    obs_count: int = 0
    months: list[int] = field(default_factory=lambda: [0] * 12)   # raw counts per month (1..12)
    description: str | None = None
    wiki_url: str | None = None
    embeddings: np.ndarray | None = None       # (n, D) student fingerprints of reference photos
    text_embedding: np.ndarray | None = None   # (D,) teacher text embedding fallback
    attributions: list[dict] = field(default_factory=list)  # {observer, license, url}


def _projected(rec: "SpeciesRecord", w, mean, project) -> "SpeciesRecord":
    from dataclasses import replace

    return replace(
        rec,
        embeddings=project(rec.embeddings, w, mean) if rec.embeddings is not None else None,
        text_embedding=project(rec.text_embedding, w, mean) if rec.text_embedding is not None else None,
    )


def months_blob(counts: list[int]) -> bytes:
    c = np.asarray(counts, dtype=np.float64)
    if c.max() <= 0:
        return bytes([128] * 12)               # no data: neutral
    return bytes(np.round(255 * c / c.max()).astype(np.uint8).tolist())


def build_pack(
    out: Path,
    region_id: str,
    region_name: str,
    pack_version: int,
    species: list[SpeciesRecord],
    curated: dict[str, dict],
    params: dict | None = None,
    sources: list[str] | None = None,
    min_photos_for_image_protos: int = 3,
    group_rules: dict | None = None,
    quick_rows: dict | None = None,
    projection: tuple | None = None,
) -> Path:
    params = {**config.DEFAULT_PARAMS, **(params or {})}
    if projection is not None:
        from .projection import apply as project

        w, mean = projection
        species = [_projected(r, w, mean, project) for r in species]
    dim = int(projection[0].shape[0]) if projection is not None else config.EMBED_DIM
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()
    con = sqlite3.connect(out)
    con.executescript(SCHEMA_SQL)
    meta = {
        "schema_version": SCHEMA_VERSION,
        "region_id": region_id,
        "region_name": region_name,
        "pack_version": pack_version,
        "built_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "model_id": config.MODEL_ID,
        "embedding_dim": dim,
        "input_dim": config.EMBED_DIM,
        "sources": json.dumps(sources or []),
        **params,
    }
    con.executemany("INSERT INTO meta VALUES (?, ?)", [(k, str(v)) for k, v in meta.items()])
    if projection is not None:
        w, mean = projection
        con.execute(
            "INSERT INTO projection VALUES (1, ?, ?, ?, ?)",
            (int(w.shape[0]), int(w.shape[1]), w.astype("<f4").tobytes(), mean.astype("<f4").tobytes()),
        )

    name_to_id: dict[str, int] = {}
    skipped = 0
    sid = 0
    for rec in species:
        has_img = rec.embeddings is not None and len(rec.embeddings) >= min_photos_for_image_protos
        if not has_img and rec.text_embedding is None:
            skipped += 1
            continue
        sid += 1
        name_to_id[rec.scientific_name.lower()] = sid
        con.execute(
            "INSERT INTO species VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                sid, rec.inat_taxon_id, rec.gbif_key, rec.scientific_name, rec.common_name,
                json.dumps(rec.common_names) if rec.common_names else None,
                rec.kingdom, rec.family, rec.genus, int(rec.obs_count), months_blob(rec.months),
                rec.description, rec.wiki_url,
            ),
        )
        if has_img:
            for blob, scale in build_species_prototypes(rec.embeddings.astype(np.float32), seed=sid):
                con.execute("INSERT INTO prototype (species_id, vec, scale, kind) VALUES (?,?,?,'image')", (sid, blob, scale))
        if rec.text_embedding is not None and (not has_img or len(rec.embeddings) < 10):
            blob, scale = quantize(rec.text_embedding)
            con.execute("INSERT INTO prototype (species_id, vec, scale, kind) VALUES (?,?,?,'text')", (sid, blob, scale))
        for a in rec.attributions:
            con.execute(
                "INSERT INTO attribution (species_id, observer, license, url) VALUES (?,?,?,?)",
                (sid, a.get("observer"), a["license"], a["url"]),
            )

    # Content and lookalikes (after all species exist so lookalikes can link inside the pack).
    # Species entry > genus rule > family rule; species with none get "unknown" (treated as unsafe).
    rules = load_group_rules() if group_rules is None else group_rules
    quick = load_quick() if quick_rows is None else quick_rows
    covered = {"species": 0, "quick": 0, "genus": 0, "family": 0, "none": 0}
    for rec in species:
        key = rec.scientific_name.lower()
        if key not in name_to_id:
            continue
        entry = curated.get(key)
        content = resolve(curated, rules, rec.scientific_name, rec.genus, rec.family, quick)
        covered[content["level"] if content else "none"] += 1
        con.execute("INSERT INTO content VALUES (?, ?)", (name_to_id[key], json.dumps(content or finalize(None))))
        for lk in (entry or {}).get("lookalikes") or []:
            con.execute(
                "INSERT INTO lookalike VALUES (?,?,?,?)",
                (name_to_id[key], name_to_id.get(lk["name"].lower()), lk["name"], lk["danger"]),
            )
    con.commit()
    con.execute("VACUUM")
    con.close()
    if skipped:
        print(f"{region_id}: skipped {skipped} species with no usable photos or text prototype")
    print(f"{region_id}: safety coverage {covered}")
    return out


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def update_index(index_path: Path, pack_path: Path, url: str) -> dict:
    """Add or replace a pack entry in index.json (hosted alongside packs on GitHub Releases)."""
    con = sqlite3.connect(pack_path)
    meta = dict(con.execute("SELECT key, value FROM meta").fetchall())
    n_species = con.execute("SELECT count(*) FROM species").fetchone()[0]
    con.close()
    index = json.loads(index_path.read_text()) if index_path.exists() else {"schema": 1, "packs": []}
    index["model_id"] = meta["model_id"]
    entry = {
        "region_id": meta["region_id"],
        "name": meta["region_name"],
        "version": int(meta["pack_version"]),
        "url": url,
        "sha256": sha256(pack_path),
        "size": pack_path.stat().st_size,
        "species_count": n_species,
        "photos_url": None,
        "photos_sha256": None,
        "photos_size": 0,
    }
    index["packs"] = [p for p in index["packs"] if p["region_id"] != entry["region_id"]] + [entry]
    index["packs"].sort(key=lambda p: p["region_id"])
    index_path.write_text(json.dumps(index, indent=2))
    return index
