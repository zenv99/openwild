"""Find an openly licensed photo for every species in the deadly collection.

Order, for the scientific name and each alias: Wikimedia Commons (category and Wikidata
P18), then an iNaturalist research-grade photo, then GBIF. CC0, CC BY and CC BY-SA come
first. CC BY-NC is kept only when nothing else exists, because the app is non-commercial.
ND licences, Plants For A Future and tropical.theferns.info are never used.

Herbarium sheets are stored with stage ``exact_specimen`` so the app still shows them.
A plain ``specimen`` stage is hidden.
"""
from __future__ import annotations

import csv
import os
import re
from pathlib import Path

from . import config
from .deadly import DEADLY_DIR, IMG_DIR, PHOTO_FIELDS, PHOTOS_CSV, SPECIES_CSV, load_species
from .deadly_catalog import CSV_FIELDS, write_csv
from .http import get_json
from .pack import encode_photo
from .photo_batch import commons_candidates, download_verified, gbif_candidates

PREFERRED = ("cc0", "cc-by", "cc-by-sa")
BLOCKED = ("pfaf.org", "theferns.info")


def _slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "species"


_MAP = re.compile(r"distribution|range[_ ]map|distrbution|locator|diagram", re.I)
_BINOM = re.compile(r"([A-Z][a-z]{2,})[ _]([a-z]{3,})")


def _ok(row: dict) -> bool:
    blob = " ".join([row.get("image_url") or "", row.get("page") or ""]).lower()
    if any(host in blob for host in BLOCKED):
        return False
    return bool(config.photo_license(row.get("license")))


def _depicts_other(page: str, scientific: str, aliases: set[str]) -> bool:
    """True when the filename names a different species in this genus, or is a map/diagram."""
    text = (page or "").replace("%20", " ").replace("_", " ")
    if _MAP.search(text):
        return True
    low = text.lower()
    if low.endswith(".png") or ".png?" in low or low.endswith(".svg"):
        epithet = scientific.split()[-1].lower()
        if epithet not in low:
            return True
    genus = scientific.split()[0].lower()
    allowed = {scientific.lower(), *(a.lower() for a in aliases)}
    for g, epithet in _BINOM.findall(text):
        stated = f"{g} {epithet}".lower()
        if stated in allowed:
            continue
        # A truncated epithet (degeni / degenii, olearus / olearius) is still this species.
        if g.lower() == genus and any(stated.split()[-1][:5] == a.split()[-1][:5] for a in allowed if a.split()[0] == genus):
            continue
        if g.lower() == genus:
            return True
    return False


def _rank(row: dict) -> tuple:
    lic = config.photo_license(row.get("license")) or ""
    prefer = 0 if lic in PREFERRED else 1
    sheet = 1 if row.get("kind") == "specimen" else 0
    return (prefer, sheet)


def _lookup(name: str, aliases: set[str] | None = None, use_commons: bool = True) -> list[dict]:
    """Candidate photos for one name. Research-grade iNaturalist only."""
    aliases = aliases or set()
    found: list[dict] = []

    def usable(rows: list[dict]) -> list[dict]:
        out = []
        for r in rows:
            if not _ok(r):
                continue
            if _depicts_other(r.get("page") or r.get("image_url") or "", name, aliases):
                continue
            out.append(r)
        return out

    if use_commons:
        try:
            found.extend(usable(commons_candidates(name, limit=8)))
        except Exception as exc:  # noqa: BLE001
            print(f"commons {name}: {exc!r}", flush=True)
    living = [r for r in found if r.get("kind") != "specimen"]
    if len(living) >= 2:
        return found
    try:
        taxa = get_json("https://api.inaturalist.org/v1/taxa", {
            "q": name, "rank": "species", "per_page": 4, "is_active": "true",
        })
        taxon = None
        for t in taxa.get("results") or []:
            if (t.get("name") or "").lower() == name.lower() or (t.get("matched_term") or "").lower() == name.lower():
                taxon = t
                break
        if taxon:
            research = get_json("https://api.inaturalist.org/v1/observations", {
                "taxon_id": taxon["id"], "photos": "true", "quality_grade": "research",
                "photo_license": "cc0,cc-by,cc-by-sa,cc-by-nc,cc-by-nc-sa",
                "per_page": 8, "order_by": "votes", "order": "desc",
            })
            for obs in research.get("results") or []:
                tax = obs.get("taxon") or {}
                tname = (tax.get("name") or "").lower()
                if tax.get("rank") not in ("species", "subspecies", "variety", "form"):
                    continue
                if tname != taxon["name"].lower() and not tname.startswith(taxon["name"].lower() + " "):
                    continue
                user = obs.get("user") or {}
                observer = (user.get("name") or user.get("login") or "").strip()
                page = obs.get("uri") or f"https://www.inaturalist.org/observations/{obs.get('id')}"
                for ph in obs.get("photos") or []:
                    lic = config.photo_license(ph.get("license_code"))
                    url = ph.get("url") or ""
                    if not lic or not url:
                        continue
                    medium = re.sub(r"/(square|small|thumb|large|original)\.", "/medium.", url)
                    found.append({
                        "scientific_name": name, "image_url": medium, "license": lic,
                        "observer": observer, "page": page, "kind": "field", "source": "inaturalist",
                    })
    except Exception as exc:  # noqa: BLE001
        print(f"inat {name}: {exc!r}", flush=True)
    living = [r for r in found if r.get("kind") != "specimen"]
    if living:
        return found
    try:
        field, specimen = gbif_candidates(name, None, limit=6)
        found.extend(usable(field))
        found.extend(usable(specimen))
    except Exception as exc:  # noqa: BLE001
        print(f"gbif {name}: {exc!r}", flush=True)
    return found


def _stage(row: dict) -> str:
    kind = row.get("kind") or "field"
    if kind == "specimen":
        return "exact_specimen"
    if kind in ("flower", "fruit", "leaves", "closeup", "seedling", "bark", "field"):
        return kind
    return "field"


def _save(name: str, row: dict, dest: Path) -> bool:
    if dest.exists() and dest.stat().st_size > 0:
        return True
    tmp = dest.with_suffix(".src.jpg")
    if not download_verified(row["image_url"], tmp):
        tmp.unlink(missing_ok=True)
        return False
    enc = encode_photo(tmp)
    tmp.unlink(missing_ok=True)
    if not enc or not enc[1]:
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(enc[1])
    return True


def fetch(per_species: int = 2, only_missing: bool = True) -> dict:
    """Download display images and rewrite photos.csv. Returns the coverage summary."""
    os.environ.setdefault("OW_INAT_INTERVAL", "1.0")
    rows = load_species()
    have: dict[str, list[dict]] = {}
    if only_missing and PHOTOS_CSV.exists():
        for r in csv.DictReader(PHOTOS_CSV.open(encoding="utf-8")):
            if (DEADLY_DIR / (r.get("file") or "")).exists():
                have.setdefault(r["scientific_name"].lower(), []).append(r)
    IMG_DIR.mkdir(parents=True, exist_ok=True)
    missing_notes = []
    for i, row in enumerate(rows, 1):
        name = row["scientific_name"]
        key = name.lower()
        if len(have.get(key) or []) >= per_species:
            continue
        labels = [name] + [a.strip() for a in (row.get("aliases") or "").split(";") if a.strip()]
        pool: list[dict] = []
        seen = {r.get("image_url") for r in have.get(key) or []}
        alias_set = set(labels)
        for label in labels:
            for cand in _lookup(label, alias_set):
                url = cand.get("image_url") or ""
                if not url or url in seen:
                    continue
                seen.add(url)
                pool.append(cand)
            living = [c for c in pool if c.get("kind") != "specimen"]
            if len(living) >= per_species:
                break
        pool.sort(key=_rank)
        # Prefer two different stages when we can.
        chosen = []
        stages = set()
        for cand in pool:
            if len(chosen) >= per_species:
                break
            stage = _stage(cand)
            if stage in stages and any(_stage(c) != stage for c in pool):
                continue
            token = f"{_slug(name)}-{len(have.get(key) or []) + len(chosen) + 1}.webp"
            dest = IMG_DIR / token
            if not _save(name, cand, dest):
                continue
            stages.add(stage)
            chosen.append({
                "scientific_name": name,
                "image_url": cand.get("image_url") or "",
                "license": config.photo_license(cand.get("license")) or "",
                "observer": re.sub(r"<[^>]+>", "", cand.get("observer") or "").replace("&amp;", "&")[:200],
                "page": cand.get("page") or cand.get("image_url") or "",
                "stage": stage,
                "source": cand.get("source") or "",
                "file": f"img/{token}",
            })
        if len(have.get(key) or []) + len(chosen) < per_species:
            # Commons candidates that will not download (rate limit, or only maps) must not
            # hide an iNaturalist or GBIF photo.
            for label in labels:
                for cand in _lookup(label, alias_set, use_commons=False):
                    url = cand.get("image_url") or ""
                    if not url or url in seen:
                        continue
                    seen.add(url)
                    if len(chosen) + len(have.get(key) or []) >= per_species:
                        break
                    token = f"{_slug(name)}-{len(have.get(key) or []) + len(chosen) + 1}.webp"
                    dest = IMG_DIR / token
                    if not _save(name, cand, dest):
                        continue
                    chosen.append({
                        "scientific_name": name,
                        "image_url": url,
                        "license": config.photo_license(cand.get("license")) or "",
                        "observer": re.sub(r"<[^>]+>", "", cand.get("observer") or "").replace("&amp;", "&")[:200],
                        "page": cand.get("page") or url,
                        "stage": _stage(cand),
                        "source": cand.get("source") or "",
                        "file": f"img/{token}",
                    })
        if chosen:
            have.setdefault(key, []).extend(chosen)
            print(f"[{i}/{len(rows)}] {name}: {len(chosen)} photo(s)", flush=True)
        elif not have.get(key):
            missing_notes.append((name, "no openly licensed photo on Commons, iNaturalist research grade, or GBIF"))
            print(f"[{i}/{len(rows)}] {name}: NONE", flush=True)
        else:
            print(f"[{i}/{len(rows)}] {name}: kept {len(have[key])}", flush=True)
    # Second chance: a preferred-licence living photo should replace a sheet-only row when one appears.
    written = []
    for row in rows:
        written.extend(have.get(row["scientific_name"].lower()) or [])
    with PHOTOS_CSV.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=PHOTO_FIELDS)
        w.writeheader()
        for r in written:
            w.writerow({k: r.get(k, "") for k in PHOTO_FIELDS})
    note_path = DEADLY_DIR / "missing.csv"
    with note_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["scientific_name", "why"])
        w.writerows(missing_notes)
    from .deadly import coverage

    summary = coverage()
    print(
        f"deadly photos: {summary['with_photo']}/{summary['species']} species, "
        f"{summary['photo_rows']} files, {summary['missing_photo']} missing",
        flush=True,
    )
    return summary


def rebuild_species_csv() -> int:
    """Rewrite species.csv from the catalog. Photo files are left in place."""
    return write_csv(SPECIES_CSV)


def export_species(rows: list[dict]) -> None:
    with SPECIES_CSV.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        w.writeheader()
        for row in rows:
            w.writerow({k: row.get(k, "") for k in CSV_FIELDS})
