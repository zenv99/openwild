"""Weekly Bali photo backfill.

The published pack is not missing photos because those species have none. A full GBIF crawl
dropped most species (errors from that crawl are swallowed, and nothing was saved per species),
so common plants with thousands of Creative Commons field photos — oleander, glory lily, guava —
shipped with no picture. Re-running that crawl would roll the dice again and can drop photos
that already shipped.

This module pins the photos already in a pack, then adds a small batch of missing species each
week. Order: iNaturalist field photos, then GBIF field photos, then Wikimedia Commons, and a
herbarium sheet only when no field photo exists. ND licences are refused. Plants For A Future
and tropical.theferns.info are never used.
"""
from __future__ import annotations

import csv
import hashlib
import re
import sqlite3
import urllib.parse
from pathlib import Path

from . import config
from .gbif_photos import _download, photo_candidates
from .http import get_json

MANIFEST_FIELDS = ["scientific_name", "image_url", "license", "observer", "page", "kind", "source", "batch"]
STATUS_FIELDS = ["scientific_name", "state", "batch", "note"]
TERMINAL = {"have", "found", "none"}
INAT_API = "https://api.inaturalist.org/v1"
COMMONS_API = "https://commons.wikimedia.org/w/api.php"
WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"
SPECIMEN_RE = re.compile(r"herbarium|exsiccat|preserved specimen|type specimen|herbarium sheet", re.I)
TAG_RE = re.compile(r"<[^>]+>")
INAT_PHOTO_RE = re.compile(r"inaturalist\.org/photos/(\d+)")
OCCURRENCE_RE = re.compile(r"gbif\.org/occurrence/(\d+)")
IMAGE_RE = re.compile(r"\.(jpe?g|png|webp)(?:$|\?)", re.I)
INAT_SIZE_RE = re.compile(r"/(square|small|thumb|medium|large|original)\.(jpe?g|png|gif|webp)$", re.I)


def manifest_path(region_dir: Path) -> Path:
    return region_dir / "photo_manifest.csv"


def status_path(region_dir: Path) -> Path:
    return region_dir / "photo_status.csv"


def _dedupe_manifest(rows: list[dict]) -> list[dict]:
    seen: set[tuple[str, str]] = set()
    out = []
    for r in rows:
        key = ((r.get("scientific_name") or "").lower(), r.get("image_url") or "")
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    return out


def read_rows(path: Path) -> list[dict]:
    if not path.exists() or path.stat().st_size == 0:
        return []
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def write_rows(path: Path, rows: list[dict], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow({k: (r.get(k) or "") for k in fields})


def inat_medium_url(url: str) -> str:
    """Ask iNaturalist for the medium derivative instead of the square thumbnail or the original."""
    return INAT_SIZE_RE.sub(r"/medium.\2", url)


def _plain(text: str | None, limit: int = 180) -> str:
    s = TAG_RE.sub(" ", text or "")
    s = re.sub(r"\s+", " ", s).strip()
    return s[:limit]


def _allowed(raw: str | None) -> str | None:
    """Return the short licence id, or None when it is missing, unknown, or no-derivatives."""
    if not raw:
        return None
    if config.license_allowed(raw):
        return config.normalize_license(raw)
    from .gbif_photos import normalize_license_url

    lic = normalize_license_url(raw)
    if lic in config.ALLOWED_LICENSES:
        return lic
    return None


def priority_key(row: dict, curated: dict, quick: dict, rules: dict, lookalikes: set[str]) -> tuple:
    """Lower sorts first. Tiers: deadly names, serious names, curated survival food, toxic one-liners,
    edible one-liners, deadly/serious genera, other safety text, then the rest (common species first)."""
    name = (row.get("scientific_name") or "").lower()
    count = -float(row.get("gbif_count") or 0)
    look = 0 if name in lookalikes else 1
    genus = (row.get("genus") or name.split(" ")[0]).lower()

    def pack(tier: int) -> tuple:
        return (tier, look, count, name)

    if name in curated:
        e = curated[name]
        tox = e.get("toxicity") or ""
        ed = e.get("edibility") or ""
        surv = (e.get("survival") or {}).get("priority")
        if tox == "deadly" or ed == "deadly":
            return pack(0)
        if tox == "serious" or ed == "toxic":
            return pack(1)
        if ed in ("edible", "edible_with_preparation") or surv in ("high_value", "use_with_caution"):
            return pack(2)
        return pack(6)
    if name in quick:
        v = quick[name].get("verdict") or ""
        return pack({"deadly": 0, "toxic": 3, "edible": 4, "edible_cooked": 4, "psychoactive": 6}.get(v, 7))
    rule = rules.get(("genus", genus))
    if rule:
        if rule.get("toxicity") in ("deadly", "serious"):
            return pack(5)
        return pack(8)
    return pack(9)


def lookalike_names(curated: dict) -> set[str]:
    out = set()
    for e in curated.values():
        for lk in e.get("lookalikes") or []:
            n = (lk.get("name") or "").strip().lower()
            if n:
                out.add(n)
    return out


def select_batch(rows: list[dict], status: dict[str, dict], *, limit: int, max_tier: int | None,
                 curated: dict, quick: dict, rules: dict, lookalikes: set[str]) -> list[dict]:
    """Species not yet resolved, highest priority first. Duplicate names are skipped."""
    pending = []
    seen: set[str] = set()
    for r in rows:
        name = (r.get("scientific_name") or "").strip()
        key = name.lower()
        if not key or key in seen:
            continue
        state = (status.get(key) or {}).get("state") or ""
        if state in TERMINAL:
            continue
        rank = priority_key(r, curated, quick, rules, lookalikes)
        if max_tier is not None and rank[0] > max_tier:
            continue
        seen.add(key)
        pending.append((rank, r))
    pending.sort(key=lambda item: item[0])
    return [r for _, r in pending[:limit]]


def _photo(name: str, image_url: str, license_: str, observer: str, page: str, kind: str, source: str) -> dict:
    return {
        "scientific_name": name,
        "image_url": image_url,
        "license": license_,
        "observer": observer,
        "page": page,
        "kind": kind,
        "source": source,
    }


def inat_candidates(name: str, limit: int = 8) -> list[dict]:
    """Creative Commons field photos from iNaturalist. Research grade first, then other grades.
    The observation's taxon must be this species (or a subspecies of it), not a genus-level guess."""
    taxa = get_json(f"{INAT_API}/taxa", {"q": name, "rank": "species", "per_page": 5, "is_active": "true"})
    taxon = None
    want = name.lower()
    for t in taxa.get("results") or []:
        if t.get("rank") != "species":
            continue
        accepted = (t.get("name") or "").lower()
        matched = (t.get("matched_term") or "").lower()
        if accepted == want or matched == want:
            taxon = t
            break
    if not taxon:
        return []
    accepted = taxon["name"]
    out: list[dict] = []
    seen: set[str] = set()

    def take(results: list[dict]) -> None:
        for obs in results:
            if len(out) >= limit:
                return
            tax = obs.get("taxon") or {}
            tname = tax.get("name") or ""
            if tax.get("rank") not in ("species", "subspecies", "variety", "form"):
                continue
            if tname.lower() != accepted.lower() and not tname.lower().startswith(accepted.lower() + " "):
                continue
            user = obs.get("user") or {}
            observer = (user.get("name") or user.get("login") or "").strip()
            page = obs.get("uri") or ""
            if obs.get("id") and not page:
                page = f"https://www.inaturalist.org/observations/{obs['id']}"
            for ph in obs.get("photos") or []:
                if ph.get("hidden") or len(out) >= limit:
                    continue
                lic = _allowed(ph.get("license_code"))
                url = inat_medium_url(ph.get("url") or "")
                if not lic or not url or url in seen:
                    continue
                seen.add(url)
                out.append(_photo(name, url, lic, observer, page, "field", "inat"))

    research = get_json(f"{INAT_API}/observations", {
        "taxon_id": taxon["id"], "photos": "true",
        "photo_license": "cc0,cc-by,cc-by-sa,cc-by-nc,cc-by-nc-sa",
        "quality_grade": "research", "per_page": max(limit, 10),
        "order_by": "votes", "order": "desc",
    })
    take(research.get("results") or [])
    if len(out) < limit:
        wider = get_json(f"{INAT_API}/observations", {
            "taxon_id": taxon["id"], "photos": "true",
            "photo_license": "cc0,cc-by,cc-by-sa,cc-by-nc,cc-by-nc-sa",
            "per_page": max(limit, 10), "order_by": "votes", "order": "desc",
        })
        take(wider.get("results") or [])
    return out


def gbif_candidates(name: str, gbif_key: str | None, limit: int = 8) -> tuple[list[dict], list[dict]]:
    """(field, specimen) from GBIF occurrence media. Raises if GBIF does not answer.

    An empty pair means GBIF answered and listed no still image. A listed count with no parsed
    photo is treated as a failure so the species stays in the queue instead of being recorded as
    having no photo.
    """
    if gbif_key and str(gbif_key).isdigit():
        key = int(gbif_key)
    else:
        m = get_json(f"{config.GBIF_API}/species/match", {"name": name, "strict": "true"})
        if m.get("matchType") == "NONE" or not m.get("usageKey"):
            return [], []
        key = int(m["usageKey"])
    probe = get_json(f"{config.GBIF_API}/occurrence/search",
                     {"taxonKey": key, "mediaType": "StillImage", "limit": 1})
    if int(probe.get("count") or 0) <= 0 and not probe.get("results"):
        return [], []
    field, specimen = [], []
    for c in photo_candidates(key, limit):
        lic = c.get("license")
        if lic not in config.ALLOWED_LICENSES or not c.get("url"):
            continue
        row = _photo(name, c["url"], lic, c.get("observer") or "", c.get("page") or "", c.get("kind") or "field", "gbif")
        (specimen if row["kind"] == "specimen" else field).append(row)
    return field[:limit], specimen[:4]


def _commons_from_imageinfo(name: str, pages: dict) -> list[dict]:
    out = []
    for page in pages.values():
        title = page.get("title") or ""
        info = (page.get("imageinfo") or [None])[0]
        if not info:
            continue
        meta = info.get("extmetadata") or {}
        lic = _allowed((meta.get("LicenseShortName") or {}).get("value"))
        if not lic:
            continue
        mime = (info.get("mime") or "").lower()
        thumb = info.get("thumburl") or ""
        full = info.get("url") or ""
        image = ""
        if thumb.lower().split("?")[0].endswith((".jpg", ".jpeg", ".png", ".webp")):
            image = thumb
        elif mime in ("image/jpeg", "image/png", "image/webp") and full:
            image = full
        if not image:
            continue
        blob = " ".join([
            title,
            (meta.get("ImageDescription") or {}).get("value") or "",
            (meta.get("Categories") or {}).get("value") or "",
        ])
        kind = "specimen" if SPECIMEN_RE.search(blob) else "field"
        artist = _plain((meta.get("Artist") or {}).get("value")) or (info.get("user") or "") or "Wikimedia Commons contributor"
        page_url = info.get("descriptionurl") or ""
        out.append(_photo(name, image, lic, artist, page_url, kind, "commons"))
    out.sort(key=lambda r: r["kind"] == "specimen")
    return out


def _commons_imageinfo(titles: list[str]) -> dict:
    if not titles:
        return {}
    data = get_json(COMMONS_API, {
        "action": "query", "format": "json", "prop": "imageinfo",
        "titles": "|".join(titles[:20]),
        "iiprop": "url|extmetadata|user|mime", "iiurlwidth": "800",
    })
    return (data.get("query") or {}).get("pages") or {}


def commons_candidates(name: str, limit: int = 8) -> list[dict]:
    """Files in Category:<scientific name>, plus the Wikidata P18 image when the category has no field photo."""
    if re.search(r'["\\{}|]', name):
        return []
    listed = get_json(COMMONS_API, {
        "action": "query", "format": "json", "list": "categorymembers",
        "cmtitle": f"Category:{name}", "cmtype": "file", "cmlimit": "20",
    })
    titles = [m.get("title") for m in (listed.get("query") or {}).get("categorymembers") or [] if m.get("title")]
    got = _commons_from_imageinfo(name, _commons_imageinfo(titles))
    if any(r["kind"] != "specimen" for r in got):
        return got[:limit]
    query = (
        'SELECT ?image WHERE { ?t wdt:P225 "' + name + '" ; wdt:P18 ?image . }'
    )
    try:
        data = get_json(WIKIDATA_SPARQL, {"query": query, "format": "json"})
    except Exception:
        return got[:limit]
    extra_titles = []
    for b in (data.get("results") or {}).get("bindings") or []:
        url = (b.get("image") or {}).get("value") or ""
        if "Special:FilePath/" not in url:
            continue
        file_name = urllib.parse.unquote(url.split("Special:FilePath/", 1)[1])
        extra_titles.append(file_name if file_name.startswith("File:") else f"File:{file_name}")
    if extra_titles:
        got.extend(_commons_from_imageinfo(name, _commons_imageinfo(extra_titles)))
    # De-duplicate and keep field photos ahead of sheets.
    seen = set()
    uniq = []
    for r in sorted(got, key=lambda r: r["kind"] == "specimen"):
        if r["image_url"] in seen:
            continue
        seen.add(r["image_url"])
        uniq.append(r)
    return uniq[:limit]


def _alt_inat_url(url: str) -> str | None:
    """iNaturalist open-data objects are either .jpg or .jpeg. A pinned pack URL does not say which."""
    if "inaturalist-open-data" not in url:
        return None
    base, _, query = url.partition("?")
    suffix = ("?" + query) if query else ""
    low = base.lower()
    if low.endswith(".jpg"):
        return base[:-4] + ".jpeg" + suffix
    if low.endswith(".jpeg"):
        return base[:-5] + ".jpg" + suffix
    return None


def download_verified(url: str, dest: Path) -> bool:
    """Download and shrink to a JPEG. Returns False when the URL is not an image."""
    if _download(url, dest):
        return True
    alt = _alt_inat_url(url)
    return bool(alt) and _download(alt, dest)


def search_photos(name: str, gbif_key: str | None, want: int = 2) -> tuple[list[dict], str]:
    """Up to `want` field photos, or specimen photos when that is all that exists.

    Returns (photos, outcome) with outcome 'found', 'none', or 'error'. 'error' means a source
    failed or every candidate download failed — the species stays in the queue. 'none' means each
    source answered and had no usable Creative Commons / Commons photo.
    """
    field: list[dict] = []
    specimen: list[dict] = []
    errors = 0
    pool_limit = max(want * 3, 6)

    def pull(fn, *args):
        nonlocal errors
        try:
            return fn(*args)
        except Exception:
            errors += 1
            return None

    inat = pull(inat_candidates, name, pool_limit)
    if inat:
        field.extend(inat)
    # One GBIF lookup as well: iNaturalist URLs sometimes fail to download, and GBIF is the
    # source that already backs the published pack. Commons is only opened when there is still
    # no field photo. Herbarium sheets stay in `specimen` until field photos run out.
    gb = pull(gbif_candidates, name, gbif_key, pool_limit)
    if gb:
        field.extend(gb[0])
        specimen.extend(gb[1])
    if len(field) < want:
        commons = pull(commons_candidates, name, pool_limit)
        if commons:
            for r in commons:
                (specimen if r["kind"] == "specimen" else field).append(r)

    pool = field if field else specimen
    if not pool:
        return [], ("error" if errors else "none")
    # Downloads happen in the batch runner so tests can stop here. Outcome is decided after
    # verification; this function only finds candidates.
    return pool[:pool_limit], "found"


def verify_candidates(cands: list[dict], dest_dir: Path, want: int) -> list[dict]:
    """Keep candidates whose image actually downloads. Stops after `want` successes."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    ok = []
    for c in cands:
        if len(ok) >= want:
            break
        token = hashlib.sha1(c["image_url"].encode()).hexdigest()[:12]
        dest = dest_dir / f"{token}.jpg"
        if download_verified(c["image_url"], dest):
            ok.append(c)
    return ok


def baseline_from_pack(pack: Path, manifest: Path, status: Path) -> dict:
    """Pin photos already shipped, so the next pack build does not depend on a flaky recrawl.

    iNaturalist photo-page credits become open-data medium URLs (.jpg, with .jpeg tried on download).
    Species whose only credits are specimen portals stay marked `have` and are refetched from GBIF
    when the pack is rebuilt — they are not spent out of the weekly batch.
    """
    con = sqlite3.connect(pack)
    try:
        meta = dict(con.execute("SELECT key, value FROM meta"))
        version = meta.get("pack_version", "pack")
        source = f"pack:{version}"
        pinned = []
        have_inat = set()
        have_any = set()
        for name, url, lic, observer in con.execute(
            """SELECT s.scientific_name, p.url, p.license, COALESCE(p.observer, '')
               FROM photo p JOIN species s ON s.id = p.species_id
               ORDER BY s.scientific_name, p.rank"""
        ):
            have_any.add(name)
            m = INAT_PHOTO_RE.search(url or "")
            if not m:
                continue
            lic_ok = _allowed(lic) or (lic if lic in config.ALLOWED_LICENSES else None)
            if not lic_ok:
                continue
            have_inat.add(name)
            pinned.append(_photo(
                name,
                f"https://inaturalist-open-data.s3.amazonaws.com/photos/{m.group(1)}/medium.jpg",
                lic_ok, observer,
                url, "field", source,
            ))
    finally:
        con.close()

    pinned_names = {r["scientific_name"].lower() for r in pinned}
    kept = [r for r in read_rows(manifest)
            if not (r.get("source") or "").startswith("pack:")
            and r["scientific_name"].lower() not in pinned_names]
    write_rows(manifest, pinned + kept, MANIFEST_FIELDS)

    old = {r["scientific_name"].lower(): r for r in read_rows(status)}
    rows = []
    for name in sorted(have_any):
        if name in have_inat:
            note = "iNaturalist image URL pinned from the published pack"
        else:
            note = "no iNaturalist image URL in the published pack; refetched from GBIF when the pack is rebuilt"
        rows.append({"scientific_name": name, "state": "have", "batch": version, "note": note})
    for key, r in old.items():
        if key not in {n.lower() for n in have_any} and r.get("state") != "have":
            rows.append(r)
    rows.sort(key=lambda r: r["scientific_name"].lower())
    write_rows(status, rows, STATUS_FIELDS)
    return {"pinned_photos": len(pinned), "pinned_species": len(have_inat),
            "have_species": len(have_any), "refetch_on_publish": len(have_any - have_inat)}


def _still_image(occ: dict) -> tuple[str, str, str, str] | None:
    """First openly licensed still image on a GBIF occurrence: (url, license, observer, kind)."""
    for media in occ.get("media") or []:
        if media.get("type") not in (None, "StillImage"):
            continue
        url = media.get("identifier") or ""
        if not IMAGE_RE.search(url):
            continue
        lic = _allowed(media.get("license") or occ.get("license"))
        if not lic:
            continue
        observer = media.get("rightsHolder") or media.get("creator") or occ.get("recordedBy") or ""
        kind = "field" if occ.get("basisOfRecord") == "HUMAN_OBSERVATION" else "specimen"
        return url, lic, _plain(observer), kind
    return None


def pin_occurrence_media(pack: Path, manifest: Path, status: Path) -> dict:
    """Turn published gbif.org/occurrence credits into direct image URLs.

    Those species have a photo in the pack, but the pack stores the occurrence page, not the file.
    Pinning the file means a later pack build does not have to search GBIF again to keep them.
    """
    con = sqlite3.connect(pack)
    try:
        meta = dict(con.execute("SELECT key, value FROM meta"))
        version = meta.get("pack_version", "pack")
        source = f"pack:{version}"
        jobs: list[tuple[str, str]] = []
        for name, url in con.execute(
            """SELECT s.scientific_name, p.url FROM photo p JOIN species s ON s.id = p.species_id
               ORDER BY s.id, p.rank"""
        ):
            m = OCCURRENCE_RE.search(url or "")
            if m:
                jobs.append((name, m.group(1)))
    finally:
        con.close()

    rows = read_rows(manifest)
    have_url = {(r["scientific_name"].lower(), r.get("image_url")) for r in rows}
    per_species: dict[str, int] = {}
    added = 0
    species = set()
    seen_keys: set[str] = set()
    for name, key in jobs:
        if per_species.get(name, 0) >= 3 or key in seen_keys:
            continue
        seen_keys.add(key)
        try:
            occ = get_json(f"{config.GBIF_API}/occurrence/{key}")
        except Exception:
            continue
        still = _still_image(occ)
        if not still:
            continue
        url, lic, observer, kind = still
        if (name.lower(), url) in have_url:
            per_species[name] = per_species.get(name, 0) + 1
            continue
        page = f"https://www.gbif.org/occurrence/{key}"
        rows.append(_photo(name, url, lic, observer, page, kind, source))
        have_url.add((name.lower(), url))
        per_species[name] = per_species.get(name, 0) + 1
        added += 1
        species.add(name)
        if added % 100 == 0:
            print(f"pinned {added} occurrence images", flush=True)

    write_rows(manifest, rows, MANIFEST_FIELDS)
    pinned_names = {n.lower() for n in species}
    status_rows = []
    for r in read_rows(status):
        if r["scientific_name"].lower() in pinned_names and "refetched from GBIF" in (r.get("note") or ""):
            r = {**r, "note": "GBIF occurrence image URL pinned from the published pack"}
        status_rows.append(r)
    write_rows(status, status_rows, STATUS_FIELDS)
    return {"occurrence_photos": added, "occurrence_species": len(species)}


def pin_covered_without_file(species_csv: Path, manifest: Path, status: Path, per_species: int = 2) -> dict:
    """One GBIF lookup for covered species that still have no file URL. Pins what comes back.

    This is not the backlog. It only names already shipped in the pack whose credit page was a
    specimen portal rather than an image file.
    """
    names = {r["scientific_name"] for r in read_rows(status) if "refetched from GBIF" in (r.get("note") or "")}
    if not names:
        return {"photos": 0, "species": 0}
    keys = {}
    with open(species_csv, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["scientific_name"] in names:
                keys[r["scientific_name"]] = r.get("gbif_key")
    rows = read_rows(manifest)
    added_names = set()
    added = 0
    for name in sorted(names):
        try:
            field, specimen = gbif_candidates(name, keys.get(name), per_species)
        except Exception:
            continue
        got = 0
        for c in (field or specimen)[:per_species]:
            c["source"] = "pack:gbif"
            c["batch"] = "2026111202"
            rows.append(c)
            got += 1
            added += 1
        if got:
            added_names.add(name.lower())
            print(f"pinned {name} ({got})", flush=True)
    write_rows(manifest, _dedupe_manifest(rows), MANIFEST_FIELDS)
    status_rows = []
    for r in read_rows(status):
        if r["scientific_name"].lower() in added_names:
            r = {**r, "note": "GBIF image URL pinned from the published pack"}
        status_rows.append(r)
    write_rows(status, status_rows, STATUS_FIELDS)
    return {"photos": added, "species": len(added_names)}


def run_batch(species_csv: Path, region_dir: Path, *, limit: int, max_tier: int | None, per_species: int,
              batch_id: str, curated: dict, quick: dict, rules: dict) -> dict:
    """Resolve the next `limit` species into the manifest. Does not rebuild a pack."""
    species = list(csv.DictReader(open(species_csv, encoding="utf-8")))
    st_rows = read_rows(status_path(region_dir))
    status = {r["scientific_name"].lower(): r for r in st_rows}
    looks = lookalike_names(curated)
    chosen = select_batch(species, status, limit=limit, max_tier=max_tier, curated=curated, quick=quick,
                          rules=rules, lookalikes=looks)
    manifest = [r for r in read_rows(manifest_path(region_dir))]
    found = none = errors = 0
    scratch = config.WORK / "photos_batch" / region_dir.name
    for r in chosen:
        name = r["scientific_name"]
        try:
            cands, outcome = search_photos(name, r.get("gbif_key"), per_species)
        except Exception:
            cands, outcome = [], "error"
        verified: list[dict] = []
        if outcome == "found":
            try:
                verified = verify_candidates(cands, scratch, per_species)
            except Exception:
                verified = []
            if not verified:
                outcome = "error"
        note = ""
        if outcome == "found":
            for p in verified:
                p["batch"] = batch_id
                manifest.append(p)
            note = verified[0]["source"] + (" specimen" if verified[0]["kind"] == "specimen" else " field")
            found += 1
        elif outcome == "none":
            note = "no openly licensed photo on iNaturalist, GBIF, or Wikimedia Commons"
            none += 1
        else:
            note = "lookup or download failed; left in the queue"
            errors += 1
        status[name.lower()] = {"scientific_name": name, "state": outcome if outcome != "error" else "error",
                                 "batch": batch_id, "note": note}
        print(f"{outcome:5} {name} — {note}", flush=True)
        # Persist after each species so a stopped run resumes without repeating finished names.
        write_rows(manifest_path(region_dir), _dedupe_manifest(manifest), MANIFEST_FIELDS)
        ordered = sorted(status.values(), key=lambda r: r["scientific_name"].lower())
        write_rows(status_path(region_dir), ordered, STATUS_FIELDS)
    summary = {"chosen": len(chosen), "found": found, "none": none, "error": errors, "batch": batch_id}
    print(f"batch {batch_id}: {summary}", flush=True)
    return summary


def fetch_recorded(species_csv: Path, out_dir: Path, manifest: Path, status: Path,
                   per_species: int = 8, workers: int = 4):
    """Build photos.csv from pinned URLs plus a GBIF refetch of covered species that have no URL.

    Species still marked pending are not queried. That is the weekly batch's job, so a pack rebuild
    cannot accidentally crawl the whole backlog.
    """
    from .gbif_photos import fetch_region_photos

    out_dir.mkdir(parents=True, exist_ok=True)
    img = out_dir / "img"
    rows: list[dict] = []
    by: dict[str, list[dict]] = {}
    for r in read_rows(manifest):
        by.setdefault(r["scientific_name"].lower(), []).append(r)
    covered_download = set()
    for key, photos in by.items():
        got = 0
        for i, r in enumerate(photos):
            if got >= per_species:
                break
            token = hashlib.sha1((key + r.get("image_url", "")).encode()).hexdigest()[:16]
            dest = img / f"{token}.jpg"
            if r.get("image_url") and download_verified(r["image_url"], dest):
                rows.append({
                    "scientific_name": r["scientific_name"], "path": str(dest),
                    "license": r.get("license") or "", "observer": r.get("observer") or "",
                    "page": r.get("page") or "", "kind": r.get("kind") or "field",
                })
                got += 1
        if got:
            covered_download.add(key)

    have = {r["scientific_name"] for r in read_rows(status) if r.get("state") == "have"}
    # Refetch covered species we could not pin to a direct image URL.
    need = {n for n in have if n.lower() not in by or n.lower() not in covered_download}
    if need:
        subset = out_dir / "_refetch_species.csv"
        with open(species_csv, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            fields = reader.fieldnames or []
            subset_rows = [r for r in reader if r.get("scientific_name") in need
                           or (r.get("scientific_name") or "").lower() in {n.lower() for n in need}]
        with open(subset, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
            w.writeheader()
            w.writerows(subset_rows)
        gbif_dir = out_dir / "_gbif_refetch"
        fetch_region_photos(subset, gbif_dir, per_species=per_species, workers=min(workers, 4))
        already = {r["scientific_name"].lower() for r in rows}
        for r in read_rows(gbif_dir / "photos.csv"):
            if r["scientific_name"].lower() not in already:
                rows.append(r)

    path = out_dir / "photos.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "path", "license", "observer", "page", "kind"])
        w.writeheader()
        w.writerows(rows)
    species_n = len({r["scientific_name"].lower() for r in rows})
    print(f"{len(rows)} photos for {species_n} species (pinned manifest + GBIF refetch of unpinned covered names)")
    return path
