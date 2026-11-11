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
CREATE TABLE photo (
  id INTEGER PRIMARY KEY, species_id INTEGER NOT NULL REFERENCES species(id), rank INTEGER NOT NULL,
  thumb BLOB NOT NULL, image BLOB NOT NULL, observer TEXT, license TEXT NOT NULL, url TEXT NOT NULL
);
CREATE INDEX photo_species ON photo(species_id, rank);
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
    photos: list[dict] = field(default_factory=list)  # {path, observer, license, url}: shown in the app


IMAGE_PX = 448      # cover photo (also scaled down on the phone for list thumbnails)
IMAGE_Q = 56
EXTRA_PX = 384      # further photos, shown when swiping
EXTRA_Q = 50


def encode_photo(path, cover: bool = True) -> tuple[bytes, bytes] | None:
    """(thumbnail, image) as WebP. Thumbnails are no longer stored (empty): the app scales the cover photo
    down itself, which saves ~6 KB per species."""
    import io

    from PIL import Image

    try:
        im = Image.open(path).convert("RGB")
    except Exception:
        return None
    px, q = (IMAGE_PX, IMAGE_Q) if cover else (EXTRA_PX, EXTRA_Q)
    im.thumbnail((px, px), Image.LANCZOS)
    b = io.BytesIO()
    im.save(b, "WEBP", quality=q, method=6)
    return b"", b.getvalue()


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
    guide_only: bool = False,
    projection: tuple | None = None,
    photos_per_species: int = 3,
    photos_per_key_species: int = 4,
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

    rules_for_photos = load_group_rules() if group_rules is None else group_rules
    quick_for_photos = load_quick() if quick_rows is None else quick_rows
    # Species with real eating/poison info get extra photos (people will study them closely).
    safety_names = set(curated) | set(quick_for_photos)
    genus_rules = {t for (rank, t) in rules_for_photos if rank == "genus"}
    safety_names |= {r.scientific_name.lower() for r in species if (r.genus or "").lower() in genus_rules}
    name_to_id: dict[str, int] = {}
    skipped = 0
    sid = 0
    for rec in species:
        has_img = rec.embeddings is not None and len(rec.embeddings) >= min_photos_for_image_protos
        if not has_img and rec.text_embedding is None and not guide_only:
            skipped += 1
            continue
        if rec.scientific_name.lower() in name_to_id:  # duplicate name (e.g. synonym keys): keep the first
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
        rank = 0
        key_species = rec.scientific_name.lower() in safety_names
        limit = max(photos_per_species, photos_per_key_species) if key_species else photos_per_species
        for ph in rec.photos:
            if rank >= limit:
                break
            enc = encode_photo(ph["path"], cover=rank == 0)
            if enc is None:
                continue
            con.execute(
                "INSERT INTO photo (species_id, rank, thumb, image, observer, license, url) VALUES (?,?,?,?,?,?,?)",
                (sid, rank, enc[0], enc[1], ph.get("observer"), ph["license"], ph["url"]),
            )
            rank += 1
        for a in rec.attributions:
            con.execute(
                "INSERT INTO attribution (species_id, observer, license, url) VALUES (?,?,?,?)",
                (sid, a.get("observer"), a["license"], a["url"]),
            )

    # Content and lookalikes (after all species exist so lookalikes can link inside the pack).
    # Species entry > genus rule > family rule; species with none get "unknown" (treated as unsafe).
    rules = load_group_rules() if group_rules is None else group_rules
    quick = load_quick() if quick_rows is None else quick_rows
    quick_by_gbif = {str(r["gbif_key"]): r for r in quick.values() if r.get("gbif_key")}
    covered = {"species": 0, "quick": 0, "genus": 0, "family": 0, "none": 0}
    done: set[str] = set()
    for rec in species:
        key = rec.scientific_name.lower()
        if key not in name_to_id or key in done:
            continue
        done.add(key)
        entry = curated.get(key)
        q = quick.get(key)
        rows = quick
        if q is None and rec.gbif_key and str(rec.gbif_key) in quick_by_gbif:
            # Same species under another name (synonym): match on the GBIF taxon key instead.
            q = quick_by_gbif[str(rec.gbif_key)]
            rows = {key: q}
        # A one-liner tagged for another kingdom (e.g. a fruit category that swept up a fungal disease) is ignored.
        row_ok = not q or not q.get("kingdom") or q["kingdom"] == rec.kingdom
        content = resolve(curated, rules, rec.scientific_name, rec.genus, rec.family, rows if row_ok else {})
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
