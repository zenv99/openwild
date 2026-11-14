"""Tiny HTTP helpers (stdlib only) with on-disk caching and polite retries."""
from __future__ import annotations

import hashlib
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from . import config

USER_AGENT = "OpenWild-pipeline/0.1 (free non-commercial plant & fungi ID; github.com/openwild)"


_host_lock = threading.Lock()
_host_next: dict[str, float] = {}
# Minimum seconds between requests per host (Wikimedia asks API clients to be gentle).
MIN_INTERVAL = {"wikipedia.org": 0.08, "wikidata.org": 0.2, "wikimedia.org": 0.05, "api.gbif.org": 0.05}


def _pace(url: str) -> None:
    host = urllib.parse.urlparse(url).netloc
    gap = next((v for k, v in MIN_INTERVAL.items() if host.endswith(k)), 0.0)
    if not gap:
        return
    with _host_lock:
        now = time.monotonic()
        wait = _host_next.get(host, 0.0) - now
        _host_next[host] = max(now, _host_next.get(host, 0.0)) + gap
    if wait > 0:
        time.sleep(wait)


def get_json(url: str, params: dict | None = None, cache: bool = True, retries: int = 7) -> dict:
    if params:
        url = f"{url}?{urllib.parse.urlencode(params, doseq=True)}"
    key = hashlib.sha1(url.encode()).hexdigest()
    path = config.CACHE / "http" / f"{key}.json"
    if cache and path.exists():
        return json.loads(path.read_text())
    for attempt in range(retries):
        _pace(url)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.loads(resp.read().decode())
            if cache:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(data))
            return data
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise
            retry_after = e.headers.get("Retry-After") if e.headers else None
            delay = float(retry_after) if retry_after and retry_after.isdigit() else min(60, 2 ** (attempt + 1))
            time.sleep(delay)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            time.sleep(min(60, 2 ** (attempt + 1)))
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
