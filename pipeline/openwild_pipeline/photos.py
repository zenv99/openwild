"""Fetch selected reference photos from the iNaturalist Open Dataset bucket (no rate limits there)."""
from __future__ import annotations

import concurrent.futures as cf
import urllib.request
from pathlib import Path

from . import config
from .http import USER_AGENT
from .inat import photo_url


def photo_path(photo_id: int, extension: str) -> Path:
    return config.PHOTOS_DIR / str(photo_id % 1000).zfill(3) / f"{photo_id}.{extension}"


def _fetch_one(photo_id: int, extension: str) -> bool:
    dest = photo_path(photo_id, extension)
    if dest.exists() and dest.stat().st_size > 0:
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        req = urllib.request.Request(photo_url(photo_id, extension), headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        tmp = dest.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.rename(dest)
        return True
    except Exception:
        return False


def fetch(photos_parquet: Path, workers: int = 32) -> tuple[int, int]:
    """Download all photos listed in a selection parquet. Returns (ok, failed). Safe to re-run."""
    import duckdb
    from tqdm import tqdm

    rows = duckdb.sql(f"SELECT photo_id, extension FROM '{photos_parquet}'").fetchall()
    ok = failed = 0
    with cf.ThreadPoolExecutor(workers) as ex:
        futs = [ex.submit(_fetch_one, int(pid), ext) for pid, ext in rows]
        for f in tqdm(cf.as_completed(futs), total=len(futs), desc="photos"):
            if f.result():
                ok += 1
            else:
                failed += 1
    return ok, failed
