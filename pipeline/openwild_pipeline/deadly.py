"""Global deadly-species collection.

``content/deadly/species.csv`` is every plant and fungus species this pipeline treats as
able to kill a person (ingestion, contact, or inhalation), with the toxin, the dangerous
parts, and a source. ``content/deadly/photos.csv`` credits one or more openly licensed
photos. ``content/deadly/img/`` holds the compressed display images.

Every region pack build that passes ``include_deadly=True`` (the guide, photo, and
commercial pack commands, including WORLD) adds any of these species the region list
does not already have, and attaches a photo when that species had none. A hand-curated
species entry still wins on the wording, so a staple the project already described is
not rewritten.
"""
from __future__ import annotations

import csv
from pathlib import Path

from . import config
from .content import validate
from .pack import SpeciesRecord

DEADLY_DIR = config.REPO_ROOT / "content" / "deadly"
SPECIES_CSV = DEADLY_DIR / "species.csv"
PHOTOS_CSV = DEADLY_DIR / "photos.csv"
IMG_DIR = DEADLY_DIR / "img"

PHOTO_FIELDS = [
    "scientific_name", "image_url", "license", "observer", "page", "stage", "source", "file",
]

# Shown in the app. "specimen" is hidden on purpose (see the app's photo query).
# "example" is a labeled photo of a different species. It is shown and never fingerprinted.
SHOWN_STAGES = {"field", "flower", "fruit", "leaves", "closeup", "seedling", "bark", "exact_specimen", "example"}


def _read(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def load_species(path: Path = SPECIES_CSV) -> list[dict]:
    rows = []
    for r in _read(path):
        r = {k: (v or "").strip() for k, v in r.items() if k}
        if r.get("scientific_name"):
            rows.append(r)
    return rows


def load_photos(path: Path = PHOTOS_CSV) -> dict[str, list[dict]]:
    """scientific name (lower) -> photo rows whose file is present."""
    out: dict[str, list[dict]] = {}
    for r in _read(path):
        r = {k: (v or "").strip() for k, v in r.items() if k}
        name = (r.get("scientific_name") or "").lower()
        if name:
            out.setdefault(name, []).append(r)
    return out


def aliases_of(row: dict) -> set[str]:
    names = {row["scientific_name"].lower()}
    for part in (row.get("aliases") or "").split(";"):
        part = part.strip().lower()
        if part:
            names.add(part)
    return names


def content_entry(row: dict) -> dict:
    """Pack content for a collection row. Severity and first aid only."""
    psycho = row.get("psychoactive") in ("1", "true", "True", "yes")
    parts = [p.strip() for p in (row.get("toxic_parts") or "").split(",") if p.strip()]
    sources = [s.strip() for s in (row.get("source") or "").split(";") if s.strip()]
    entry = {
        "scientific_name": row["scientific_name"],
        "edibility": "deadly",
        "toxicity": "deadly",
        "psychoactive": psycho,
        "level": "species",
        "if_eaten": {
            "harms": {
                "toxic_parts": parts or ["see severity"],
                "cooking_makes_safe": False,
                "symptoms": row.get("symptoms") or "",
                "onset": row.get("onset") or "",
                "severity": row.get("severity") or "",
                "what_to_do": row.get("what_to_do") or "",
            }
        },
        "survival": {"priority": "avoid"},
        "sources": sources or [f"Wikipedia: {row['scientific_name']}"],
        "review": {"status": "unreviewed"},
    }
    return entry


def validate_collection(species: list[dict] | None = None, photos: dict[str, list[dict]] | None = None) -> list[str]:
    """Problems in the committed collection. Empty means it may ship."""
    species = load_species() if species is None else species
    photos = load_photos() if photos is None else photos
    problems: list[str] = []
    seen: set[str] = set()
    blocked = ("pfaf.org", "theferns.info")
    for row in species:
        name = row["scientific_name"]
        key = name.lower()
        if key in seen:
            problems.append(f"duplicate species {name}")
        seen.add(key)
        if row.get("kingdom") not in ("Plantae", "Fungi"):
            problems.append(f"{name}: kingdom must be Plantae or Fungi")
        if row.get("toxicity") != "deadly" or row.get("edibility") != "deadly":
            problems.append(f"{name}: collection rows are deadly")
        for err in validate(content_entry(row)):
            problems.append(f"{name}: {err}")
        for host in blocked:
            if host in (row.get("source") or "").lower():
                problems.append(f"{name}: source uses a blocked site")
    known = seen | {a for row in species for a in aliases_of(row)}
    for name, pics in photos.items():
        if name not in known and name not in seen:
            problems.append(f"photo for unknown species {name}")
        for pic in pics:
            if not config.photo_license(pic.get("license")):
                problems.append(f"{name}: licence {pic.get('license')!r} cannot ship")
            blob = " ".join([pic.get("image_url") or "", pic.get("page") or "", pic.get("source") or ""]).lower()
            if any(host in blob for host in blocked):
                problems.append(f"{name}: photo URL is from a blocked site")
            rel = pic.get("file") or ""
            if rel and not (DEADLY_DIR / rel).exists() and not Path(rel).exists():
                problems.append(f"{name}: missing image file {rel}")
            if (pic.get("stage") or "field") not in SHOWN_STAGES:
                problems.append(f"{name}: stage {pic.get('stage')!r} is hidden in the app")
    return problems


def _photo_dicts(name: str, photos: dict[str, list[dict]], limit: int = 3) -> list[dict]:
    rows = list(photos.get(name.lower()) or [])
    # A second view (flower, fruit, leaves) is more useful than two near-duplicate habits.
    rows.sort(key=lambda r: (r.get("stage") or "field") == "field")
    out = []
    seen_url = set()
    for r in rows:
        if len(out) >= limit:
            break
        url = r.get("page") or r.get("image_url") or ""
        if not url or url in seen_url:
            continue
        rel = r.get("file") or ""
        path = DEADLY_DIR / rel if rel and not Path(rel).is_absolute() else Path(rel) if rel else None
        if path is None or not path.exists():
            continue
        if not config.photo_license(r.get("license")):
            continue
        seen_url.add(url)
        out.append({
            "path": str(path),
            "observer": (r.get("observer") or "")[:200],
            "license": r["license"],
            "url": r.get("page") or r.get("image_url") or "",
            "stage": r.get("stage") or "field",
        })
    return out


def _record(row: dict, photos: dict[str, list[dict]]) -> SpeciesRecord:
    name = row["scientific_name"]
    wiki = "https://en.wikipedia.org/wiki/" + name.replace(" ", "_")
    return SpeciesRecord(
        scientific_name=name,
        kingdom=row.get("kingdom") or "Plantae",
        common_name=row.get("common_name") or None,
        family=row.get("family") or None,
        genus=row.get("genus") or name.split()[0],
        obs_count=0,
        description=row.get("severity") or None,
        wiki_url=wiki,
        photos=_photo_dicts(name, photos),
        attributions=[],
        always_include=True,
    )


def apply_to_pack(species: list[SpeciesRecord], curated: dict | None) -> tuple[list[SpeciesRecord], dict]:
    """Add collection species that this pack does not already list, and a photo if it had none.

    Curated species text is left as it was. Collection text fills only names with no species entry.
    """
    rows = load_species()
    if not rows:
        return species, dict(curated or {})
    photos = load_photos()
    curated = dict(curated or {})
    by_name = {rec.scientific_name.lower(): rec for rec in species}
    extra: list[SpeciesRecord] = []
    for row in rows:
        names = aliases_of(row)
        hit = next((by_name[n] for n in names if n in by_name), None)
        if hit is None:
            rec = _record(row, photos)
            extra.append(rec)
            by_name[rec.scientific_name.lower()] = rec
            for n in names:
                by_name.setdefault(n, rec)
        elif not hit.photos:
            added = _photo_dicts(row["scientific_name"], photos)
            if added:
                hit.photos = added
                hit.always_include = True
        # Hand-written species entries win. Otherwise the collection warning is the species text,
        # stored under every name the pack might use.
        if any(n in curated for n in names):
            continue
        entry = content_entry(row)
        for n in names:
            curated.setdefault(n, entry)
    return list(species) + extra, curated


def coverage(species: list[dict] | None = None, photos: dict[str, list[dict]] | None = None) -> dict:
    species = load_species() if species is None else species
    photos = load_photos() if photos is None else photos
    by_kingdom: dict[str, dict] = {}
    missing = []
    example_only = []
    with_photo = 0
    photo_rows = 0
    for row in species:
        kingdom = row.get("kingdom") or "Plantae"
        slot = by_kingdom.setdefault(kingdom, {"species": 0, "with_photo": 0})
        slot["species"] += 1
        pics = [p for p in photos.get(row["scientific_name"].lower(), []) if (DEADLY_DIR / (p.get("file") or " ")).exists()]
        photo_rows += len(pics)
        if pics:
            with_photo += 1
            slot["with_photo"] += 1
            if all((p.get("stage") or "") == "example" for p in pics):
                example_only.append(row["scientific_name"])
        else:
            missing.append(row["scientific_name"])
    return {
        "species": len(species),
        "with_photo": with_photo,
        "missing_photo": len(missing),
        "missing": missing,
        "example_only": example_only,
        "photo_rows": photo_rows,
        "by_kingdom": by_kingdom,
    }


def _gbif_accepted(name: str) -> str | None:
    """Accepted species name, or None when GBIF will not match a species."""
    from .http import get_json

    try:
        match = get_json(f"{config.GBIF_API}/species/match", {"name": name, "strict": "true"})
    except Exception:
        return None
    if match.get("matchType") in (None, "NONE"):
        return None
    if (match.get("rank") or "").upper() not in ("SPECIES", "SUBSPECIES", "VARIETY", "FORM"):
        # Synonyms often come back as SPECIES via the accepted usage.
        if (match.get("status") or "").upper() not in ("SYNONYM", "HOMOTYPIC_SYNONYM", "HETEROTYPIC_SYNONYM"):
            if (match.get("rank") or "").upper() != "SPECIES":
                return None
    accepted = match.get("species") or match.get("canonicalName") or ""
    if match.get("acceptedUsageKey") and match.get("usageKey") != match.get("acceptedUsageKey"):
        try:
            detail = get_json(f"{config.GBIF_API}/species/{int(match['acceptedUsageKey'])}")
            accepted = detail.get("canonicalName") or detail.get("species") or accepted
        except Exception:
            pass
    parts = accepted.split()
    if len(parts) < 2:
        return None
    return f"{parts[0]} {parts[1]}"


def insert_into_pack(pack: Path) -> dict:
    """Add collection species (and a photo, when the pack had none) to an existing pack file.

    Used by pack builders that do not go through ``build_pack``. Existing rows are kept.
    """
    import json
    import sqlite3

    from .content import load_curated, load_group_rules, resolve
    from .pack import encode_photo, months_blob

    pack = Path(pack)
    con = sqlite3.connect(pack)
    have_photo = {i for (i,) in con.execute("SELECT DISTINCT species_id FROM photo")}
    recs: list[SpeciesRecord] = []
    ids: dict[str, int] = {}
    for sid, name, kingdom, family, genus, common in con.execute(
        "SELECT id, scientific_name, kingdom, family, genus, common_name FROM species"
    ):
        rec = SpeciesRecord(
            scientific_name=name, kingdom=kingdom or "Plantae",
            family=family, genus=genus, common_name=common,
        )
        if sid in have_photo:
            rec.photos = [{"path": "existing", "license": "cc0", "url": "existing"}]
        recs.append(rec)
        ids[name.lower()] = sid
    merged, curated_out = apply_to_pack(recs, load_curated())
    rules = load_group_rules()
    added = photos_added = 0
    next_id = con.execute("SELECT COALESCE(MAX(id), 0) FROM species").fetchone()[0]
    for rec in merged:
        key = rec.scientific_name.lower()
        real = [p for p in rec.photos if p.get("path") not in (None, "", "existing") and Path(p["path"]).exists()]
        if key in ids:
            sid = ids[key]
            if sid in have_photo:
                continue
        else:
            next_id += 1
            sid = next_id
            ids[key] = sid
            con.execute(
                "INSERT INTO species VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (sid, None, None, rec.scientific_name, rec.common_name, None, rec.kingdom,
                 rec.family, rec.genus, int(rec.obs_count or 0), months_blob(rec.months or [0] * 12),
                 rec.description, rec.wiki_url),
            )
            content = resolve(curated_out, rules, rec.scientific_name, rec.genus, rec.family, {})
            con.execute("INSERT INTO content VALUES (?, ?)", (sid, json.dumps(content or {"edibility": "deadly", "toxicity": "deadly"})))
            added += 1
        rank = con.execute("SELECT COALESCE(MAX(rank), -1) FROM photo WHERE species_id=?", (sid,)).fetchone()[0]
        for ph in real[:3]:
            enc = encode_photo(ph["path"])
            if not enc:
                continue
            rank += 1
            lic = config.photo_license(ph.get("license"))
            if not lic:
                continue
            con.execute(
                "INSERT INTO photo (species_id, rank, thumb, image, observer, license, url, stage) VALUES (?,?,?,?,?,?,?,?)",
                (sid, rank, enc[0], enc[1], ph.get("observer"), lic, ph["url"], ph.get("stage") or "field"),
            )
            con.execute(
                "INSERT INTO attribution (species_id, observer, license, url) VALUES (?,?,?,?)",
                (sid, ph.get("observer"), lic, ph["url"]),
            )
            photos_added += 1
    con.commit()
    con.close()
    return {"species_added": added, "photos_added": photos_added}


def dedupe_accepted(rows: list[dict]) -> list[dict]:
    """Collapse names GBIF treats as one species. Keeps a curated spelling when we have one."""
    from .content import load_curated

    curated = {k.lower() for k in load_curated()}
    groups: dict[str, list[dict]] = {}
    order: list[str] = []
    for row in rows:
        accepted = _gbif_accepted(row["scientific_name"]) or row["scientific_name"]
        key = accepted.lower()
        if key not in groups:
            order.append(key)
            groups[key] = []
        groups[key].append((accepted, row))
    out = []
    for key in order:
        items = groups[key]
        # Prefer the name already used in curated YAML, else the GBIF accepted name.
        chosen_name, chosen_row = items[0][1]["scientific_name"], items[0][1]
        accepted_name = items[0][0]
        for accepted, row in items:
            if row["scientific_name"].lower() in curated:
                chosen_name, chosen_row = row["scientific_name"], row
                break
        else:
            for accepted, row in items:
                if row["scientific_name"].lower() == accepted.lower():
                    chosen_name, chosen_row = row["scientific_name"], row
                    accepted_name = accepted
                    break
            else:
                chosen_row = dict(items[0][1])
                chosen_name = accepted_name
                chosen_row["scientific_name"] = accepted_name
                chosen_row["genus"] = accepted_name.split()[0]
        merged = dict(chosen_row)
        merged["scientific_name"] = chosen_name
        alias = set()
        for accepted, row in items:
            if accepted.lower() != chosen_name.lower():
                alias.add(accepted)
            if row["scientific_name"].lower() != chosen_name.lower():
                alias.add(row["scientific_name"])
            for part in (row.get("aliases") or "").split(";"):
                if part.strip() and part.strip().lower() != chosen_name.lower():
                    alias.add(part.strip())
        merged["aliases"] = "; ".join(sorted(alias, key=str.lower))
        out.append(merged)
    return out
