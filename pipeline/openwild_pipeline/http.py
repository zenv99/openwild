"""Tiny HTTP helpers (stdlib only) with on-disk caching and polite retries."""
from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from . import config

USER_AGENT = "OpenWild-pipeline/0.1 (free non-commercial plant & fungi ID; github.com/openwild)"


def get_json(url: str, params: dict | None = None, cache: bool = True, retries: int = 4) -> dict:
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    key = hashlib.sha1(url.encode()).hexdigest()
    path = config.CACHE / "http" / f"{key}.json"
    if cache and path.exists():
        return json.loads(path.read_text())
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.loads(resp.read().decode())
            if cache:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(data))
            return data
        except (urllib.error.URLError, TimeoutError) as e:
            if isinstance(e, urllib.error.HTTPError) and e.code == 404:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError(f"GET failed after {retries} attempts: {url}")


def download(url: str, dest: Path, chunk: int = 1 << 20) -> Path:
    """Stream a (possibly huge) file to disk, resuming if a partial file exists."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    have = part.stat().st_size if part.exists() else 0
    headers = {"User-Agent": USER_AGENT}
    if have:
        headers["Range"] = f"bytes={have}-"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as resp:
        resumed = have and resp.status == 206  # server honoured Range; otherwise start over
        with open(part, "ab" if resumed else "wb") as f:
            while True:
                buf = resp.read(chunk)
                if not buf:
                    break
                f.write(buf)
    part.rename(dest)
    return dest
