"""Reference photos per species from GBIF occurrence media (iNaturalist, herbaria, other datasets).

Only openly licensed images (CC0, CC BY, CC BY-SA, CC BY-NC, CC BY-NC-SA). ND licences are skipped.
Images are downscaled to 320 px on save, so a region's photos stay small.
"""
from __future__ import annotations

import concurrent.futures as cf
import csv
import io
import re
import urllib.request
from pathlib import Path

from . import config
from .http import USER_AGENT, get_json


def normalize_license_url(url: str | None) -> str | None:
    if not url:
        return None
    u = url.lower()
    if "publicdomain/zero" in u or "cc0" in u:
        return "cc0"
    m = re.search(r"licenses/(by(?:-nc)?(?:-sa)?(?:-nd)?)", u)
    if m:
        return "cc-" + m.group(1)
    u = u.replace("_", "-").replace(" ", "-")  # GBIF enums like CC_BY_NC_4_0
    if u.startswith("cc0"):
        return "cc0"
    m = re.match(r"(cc-by(?:-nc)?(?:-sa)?(?:-nd)?)(?:-\d|$)", u)
    return m.group(1) if m else None


def smaller_url(url: str) -> str:
    """Ask iNaturalist-hosted images for the medium size instead of the original."""
    return re.sub(r"/original\.(jpe?g|png)", r"/medium.\1", url, flags=re.I)


def species_key(name: str, key: str | None) -> int | None:
    if key and str(key).isdigit():
        return int(key)
    try:
        m = get_json(f"{config.GBIF_API}/species/match", {"name": name, "strict": "true"})
    except Exception:
        return None
    return m.get("usageKey") if m.get("matchType") != "NONE" else None


def photo_candidates(taxon_key: int, want: int) -> list[dict]:
    out: list[dict] = []
    try:
        d = get_json(f"{config.GBIF_API}/occurrence/search",
                     {"taxonKey": taxon_key, "mediaType": "StillImage", "limit": 60})
    except Exception:
        return out
    for occ in d.get("results", []):
        for m in occ.get("media", [])[:1]:  # one image per observation = more variety
            lic = normalize_license_url(m.get("license") or occ.get("license"))
            if lic not in config.ALLOWED_LICENSES or not m.get("identifier"):
                continue
            out.append({
                "url": smaller_url(m["identifier"]),
                "license": lic,
                "observer": m.get("rightsHolder") or m.get("creator") or occ.get("recordedBy") or "",
                "page": m.get("references") or f"https://www.gbif.org/occurrence/{occ.get('key')}",
            })
        if len(out) >= want:
            break
    return out[:want]


def _download(url: str, dest: Path) -> bool:
    from PIL import Image

    if dest.exists():
        return True
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=40) as r:
            data = r.read()
        im = Image.open(io.BytesIO(data)).convert("RGB")
        im.thumbnail((320, 320))
        dest.parent.mkdir(parents=True, exist_ok=True)
        im.save(dest, "JPEG", quality=88)
        return True
    except Exception:
        return False


def fetch_region_photos(species_csv: Path, out_dir: Path, per_species: int = 8, workers: int = 16,
                        max_species: int | None = None) -> Path:
    """Writes out_dir/photos.csv: scientific_name, path, license, observer, page."""
    rows = list(csv.DictReader(open(species_csv, encoding="utf-8")))
    rows.sort(key=lambda r: -float(r.get("gbif_count") or 0))
    if max_species:
        rows = rows[:max_species]

    def one(r):
        key = species_key(r["scientific_name"], r.get("gbif_key"))
        if not key:
            return []
        got = []
        for i, c in enumerate(photo_candidates(key, per_species)):
            dest = out_dir / "img" / f"{key}_{i}.jpg"
            if _download(c["url"], dest):
                got.append({"scientific_name": r["scientific_name"], "path": str(dest), **{k: c[k] for k in ("license", "observer", "page")}})
        return got

    out_dir.mkdir(parents=True, exist_ok=True)
    results = []
    with cf.ThreadPoolExecutor(workers) as ex:
        for n, got in enumerate(ex.map(one, rows), 1):
            results.extend(got)
            if n % 200 == 0:
                print(f"{n}/{len(rows)} species, {len(results)} photos", flush=True)
    path = out_dir / "photos.csv"
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "path", "license", "observer", "page"])
        w.writeheader()
        w.writerows(results)
    print(f"{len(results)} photos for {len({r['scientific_name'] for r in results})} species")
    return path
