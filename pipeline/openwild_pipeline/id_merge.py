"""Fold a hidden finer pack into its country pack without dropping either side's photos.

Published Indonesia (ID) and the finer ID-BA pack share one fingerprint model. This copies the
country file, then adds species, photos, and fingerprints that exist only in the finer pack.
The same photo URL is stored once. A hidden photo URL is not copied across. Safety text keeps
the more serious toxicity, and a species-level entry wins over a genus warning of the same severity.
"""
from __future__ import annotations

import json
import shutil
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

TOX_RANK = {"": 0, "unknown": 0, "none": 1, "mild": 2, "serious": 3, "toxic": 3, "poisonous": 3, "deadly": 4}
LEVEL_RANK = {"": 0, "family": 1, "genus": 2, "quick": 3, "species": 4}


def toxicity_rank(content: dict | None) -> int:
    if not content:
        return 0
    tox = TOX_RANK.get((content.get("toxicity") or "").lower(), 0)
    edi = (content.get("edibility") or "").lower()
    if edi == "deadly":
        tox = max(tox, 4)
    elif edi == "toxic":
        tox = max(tox, 3)
    return tox


def prefer_content(parent: dict | None, child: dict | None) -> dict | None:
    """Content to keep when the same species is in the country pack and the finer pack."""
    if not child:
        return parent
    if not parent:
        return child
    child_tox, parent_tox = toxicity_rank(child), toxicity_rank(parent)
    if child_tox > parent_tox:
        return child
    if parent_tox > child_tox:
        return parent
    child_level = LEVEL_RANK.get(child.get("level") or "", 0)
    parent_level = LEVEL_RANK.get(parent.get("level") or "", 0)
    if child_level > parent_level:
        return child
    parent_edi = (parent.get("edibility") or "unknown").lower()
    child_edi = (child.get("edibility") or "unknown").lower()
    if parent_edi in ("", "unknown") and child_edi not in ("", "unknown"):
        return child
    return parent


def hidden_urls(path: Path | None) -> set[str]:
    if path is None or not path.exists():
        return set()
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("http"):
            out.add(line)
    return out


def merge_packs(parent: Path, child: Path, out: Path, hide: set[str] | None = None) -> dict:
    """Write `out` as the country pack plus anything the finer pack still adds.

    `parent` is not modified. Photo and prototype blobs are copied in SQLite, not through Python.
    """
    hide = hide or set()
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.resolve() != parent.resolve():
        shutil.copyfile(parent, out)
    dst = sqlite3.connect(out)
    dst.execute("ATTACH DATABASE ? AS finer", (str(child),))
    parent_ids = {name.lower(): sid for sid, name in dst.execute("SELECT id, scientific_name FROM species")}
    next_id = dst.execute("SELECT COALESCE(MAX(id), 0) FROM species").fetchone()[0]
    mapping: list[tuple[int, int, int]] = []  # finer_id, new_id, is_new
    added_species = 0
    for fid, name in dst.execute("SELECT id, scientific_name FROM finer.species"):
        key = name.lower()
        if key in parent_ids:
            mapping.append((fid, parent_ids[key], 0))
            continue
        next_id += 1
        parent_ids[key] = next_id
        mapping.append((fid, next_id, 1))
        added_species += 1
    dst.execute("CREATE TEMP TABLE idmap (finer_id INTEGER PRIMARY KEY, new_id INTEGER, is_new INTEGER)")
    dst.executemany("INSERT INTO idmap VALUES (?,?,?)", mapping)

    species_cols = [r[1] for r in dst.execute("PRAGMA table_info(species)")]
    copy_cols = [c for c in species_cols if c != "id"]
    col_sql = ", ".join(copy_cols)
    dst.execute(
        f"INSERT INTO species (id, {col_sql}) "
        f"SELECT m.new_id, {col_sql} FROM finer.species s JOIN idmap m ON m.finer_id = s.id WHERE m.is_new = 1"
    )
    dst.execute(
        "INSERT INTO content (species_id, json) "
        "SELECT m.new_id, c.json FROM finer.content c JOIN idmap m ON m.finer_id = c.species_id WHERE m.is_new = 1"
    )

    dst.execute("CREATE TEMP TABLE hide(url TEXT PRIMARY KEY)")
    dst.executemany("INSERT OR IGNORE INTO hide VALUES (?)", [(u,) for u in hide])

    before_photos = dst.execute("SELECT COUNT(*) FROM photo").fetchone()[0]
    dst.execute(
        """
        INSERT INTO photo (species_id, rank, thumb, image, observer, license, url, stage)
        SELECT m.new_id,
               CASE WHEN m.is_new = 1 THEN p.rank
                    ELSE (SELECT COALESCE(MAX(x.rank), -1) FROM photo x WHERE x.species_id = m.new_id)
                         + ROW_NUMBER() OVER (PARTITION BY p.species_id ORDER BY p.rank, p.id)
               END,
               p.thumb, p.image, p.observer, p.license, p.url, p.stage
        FROM finer.photo p
        JOIN idmap m ON m.finer_id = p.species_id
        WHERE p.url NOT IN (SELECT url FROM hide)
          AND p.url NOT IN (SELECT url FROM photo WHERE species_id = m.new_id)
          AND p.id = (
              SELECT MIN(p2.id) FROM finer.photo p2
              WHERE p2.species_id = p.species_id AND p2.url = p.url
          )
        """
    )
    added_photos = dst.execute("SELECT COUNT(*) FROM photo").fetchone()[0] - before_photos

    before_proto = dst.execute("SELECT COUNT(*) FROM prototype").fetchone()[0]
    dst.execute(
        """
        INSERT INTO prototype (species_id, vec, scale, kind)
        SELECT m.new_id, p.vec, p.scale, p.kind
        FROM finer.prototype p
        JOIN idmap m ON m.finer_id = p.species_id
        WHERE NOT EXISTS (
            SELECT 1 FROM prototype x WHERE x.species_id = m.new_id AND x.vec = p.vec
        )
        """
    )
    added_proto = dst.execute("SELECT COUNT(*) FROM prototype").fetchone()[0] - before_proto

    dst.execute(
        """
        INSERT INTO attribution (species_id, observer, license, url)
        SELECT m.new_id, a.observer, a.license, a.url
        FROM finer.attribution a
        JOIN idmap m ON m.finer_id = a.species_id
        WHERE a.url NOT IN (SELECT url FROM hide)
          AND NOT EXISTS (
              SELECT 1 FROM attribution x WHERE x.species_id = m.new_id AND x.url = a.url
          )
        """
    )

    # Fill a blank common name or description. Do not overwrite a name the country pack already has.
    dst.execute(
        """
        UPDATE species SET
          common_name = COALESCE(common_name, (
              SELECT s.common_name FROM finer.species s JOIN idmap m ON m.finer_id = s.id
              WHERE m.new_id = species.id AND m.is_new = 0)),
          description = COALESCE(description, (
              SELECT s.description FROM finer.species s JOIN idmap m ON m.finer_id = s.id
              WHERE m.new_id = species.id AND m.is_new = 0)),
          wiki_url = COALESCE(wiki_url, (
              SELECT s.wiki_url FROM finer.species s JOIN idmap m ON m.finer_id = s.id
              WHERE m.new_id = species.id AND m.is_new = 0))
        WHERE id IN (SELECT new_id FROM idmap WHERE is_new = 0)
        """
    )

    safety_updated = 0
    finer_content = {
        fid: json.loads(raw)
        for fid, raw in dst.execute(
            "SELECT m.finer_id, c.json FROM finer.content c JOIN idmap m ON m.finer_id = c.species_id WHERE m.is_new = 0"
        )
    }
    parent_content = {
        sid: json.loads(raw)
        for sid, raw in dst.execute(
            "SELECT species_id, json FROM content WHERE species_id IN (SELECT new_id FROM idmap WHERE is_new = 0)"
        )
    }
    id_of = {fid: new for fid, new, _is_new in mapping}
    for fid, child_c in finer_content.items():
        sid = id_of[fid]
        chosen = prefer_content(parent_content.get(sid), child_c)
        if chosen is child_c and json.dumps(chosen, sort_keys=True) != json.dumps(parent_content.get(sid), sort_keys=True):
            dst.execute("UPDATE content SET json = ? WHERE species_id = ?", (json.dumps(chosen), sid))
            safety_updated += 1

    # Lookalikes from the finer pack, pointed at whoever is now in the merged file.
    name_of = {fid: name.lower() for fid, name in dst.execute("SELECT id, scientific_name FROM finer.species")}
    have = {(a, b or 0, n.lower()) for a, b, n in dst.execute(
        "SELECT species_id, lookalike_species_id, lookalike_name FROM lookalike"
    )}
    added_look = 0
    for fid, look_id, look_name, danger in dst.execute(
        "SELECT species_id, lookalike_species_id, lookalike_name, danger FROM finer.lookalike"
    ):
        sid = id_of.get(fid)
        if sid is None:
            continue
        other = parent_ids.get((look_name or "").lower())
        if other is None and look_id is not None:
            other = parent_ids.get(name_of.get(look_id, ""))
        key = (sid, other or 0, (look_name or "").lower())
        if key in have or not look_name:
            continue
        dst.execute(
            "INSERT INTO lookalike (species_id, lookalike_species_id, lookalike_name, danger) VALUES (?,?,?,?)",
            (sid, other, look_name, danger),
        )
        have.add(key)
        added_look += 1

    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    dst.execute("UPDATE meta SET value = ? WHERE key = 'built_at'", (now,))
    dst.commit()
    stats = {
        "species": dst.execute("SELECT COUNT(*) FROM species").fetchone()[0],
        "added_species": added_species,
        "photos": dst.execute("SELECT COUNT(*) FROM photo").fetchone()[0],
        "added_photos": added_photos,
        "prototypes": dst.execute("SELECT COUNT(*) FROM prototype").fetchone()[0],
        "added_prototypes": added_proto,
        "safety_rows_updated": safety_updated,
        "lookalikes_added": added_look,
        "built_at": now,
    }
    dst.execute("DETACH DATABASE finer")
    dst.commit()
    dst.close()
    return stats


def add_display_photos(pack: Path, rows: list[dict], embeddings) -> dict:
    """Insert living photos and, when a fingerprint is provided, one image prototype each.

    `rows` uses scientific_name, file/path, license, observer, page, stage. `embeddings` is a
    float array aligned with `rows`, or None when the pack should stay guide-only.
    Existing photos and fingerprints are left in place. A species already showing a living photo
    is not given another copy of the same URL.
    """
    import numpy as np

    from .config import photo_license
    from .pack import encode_photo
    from .prototypes import quantize

    con = sqlite3.connect(pack)
    ids = {name.lower(): sid for sid, name in con.execute("SELECT id, scientific_name FROM species")}
    living = {}
    urls = {}
    for sid, stage, url in con.execute("SELECT species_id, stage, url FROM photo"):
        urls.setdefault(sid, set()).add(url)
        if stage not in ("specimen", "related", "exact_specimen"):
            living[sid] = living.get(sid, 0) + 1
    proto_n = {}
    for sid, n in con.execute("SELECT species_id, COUNT(*) FROM prototype WHERE kind = 'image' GROUP BY species_id"):
        proto_n[sid] = n
    added = 0
    fingerprinted = 0
    touched: set[int] = set()
    new_ids: set[int] = set()
    for i, row in enumerate(rows):
        sid = ids.get((row.get("scientific_name") or "").lower())
        if sid is None:
            continue
        lic = photo_license(row.get("license"))
        if lic is None:
            continue
        page = row.get("page") or row.get("url") or ""
        image_url = row.get("image_url") or ""
        # Two photos often share one observation page. Skip only the same image, not the same credit page.
        seen = urls.get(sid, set())
        if image_url and image_url in seen:
            continue
        if not image_url and page and page in seen:
            continue
        path = Path(row.get("file") or row.get("path") or "")
        if not path.exists():
            continue
        enc = encode_photo(path)
        if enc is None:
            continue
        cur = con.execute(
            "INSERT INTO photo (species_id, rank, thumb, image, observer, license, url, stage) VALUES (?,?,?,?,?,?,?,?)",
            (sid, 0, enc[0], enc[1], (row.get("observer") or "")[:200], lic, page, row.get("stage") or "field"),
        )
        new_ids.add(cur.lastrowid)
        touched.add(sid)
        added += 1
        seen = urls.setdefault(sid, set())
        if page:
            seen.add(page)
        if image_url:
            seen.add(image_url)
        living[sid] = living.get(sid, 0) + 1
        con.execute(
            "INSERT INTO attribution (species_id, observer, license, url) VALUES (?,?,?,?)",
            (sid, (row.get("observer") or "")[:200], lic, page),
        )
        # A labeled example, a relative, or a museum sheet must not become a fingerprint of this name.
        if embeddings is not None and (row.get("stage") or "") not in (
            "example", "related", "specimen", "exact_specimen",
        ) and proto_n.get(sid, 0) < 6:
            vec = np.asarray(embeddings[i], dtype="float32")
            norm = float(np.linalg.norm(vec)) or 1.0
            blob, scale = quantize(vec / norm)
            con.execute(
                "INSERT INTO prototype (species_id, vec, scale, kind) VALUES (?,?,?,'image')",
                (sid, blob, scale),
            )
            proto_n[sid] = proto_n.get(sid, 0) + 1
            fingerprinted += 1
    # New living photos lead the gallery. Pressed specimens and photos of a relative stay after them.
    borrowed = {"specimen", "related", "exact_specimen"}
    for sid in touched:
        have = con.execute("SELECT id, stage FROM photo WHERE species_id = ?", (sid,)).fetchall()
        lead = [pid for pid, stage in have if stage not in borrowed]
        tail = [pid for pid, stage in have if stage in borrowed]
        lead.sort(key=lambda pid: (0 if pid in new_ids else 1, pid))
        for rank, pid in enumerate(lead + tail):
            con.execute("UPDATE photo SET rank = ? WHERE id = ?", (rank, pid))
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    version = datetime.now(timezone.utc).strftime("%Y%m%d%H")
    previous = con.execute("SELECT value FROM meta WHERE key = 'pack_version'").fetchone()
    if previous and previous[0].isdigit() and int(previous[0]) >= int(version):
        version = str(int(previous[0]) + 1)
    con.execute("UPDATE meta SET value = ? WHERE key = 'pack_version'", (version,))
    con.execute("UPDATE meta SET value = ? WHERE key = 'built_at'", (now,))
    con.commit()
    con.close()
    return {"photos_added": added, "prototypes_added": fingerprinted, "pack_version": int(version)}
