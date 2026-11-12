"""Fetch the landing-page photos from Wikimedia Commons, only if openly licensed.

Writes android/app/src/main/res/drawable-nodpi/hero_<key>.webp and
android/app/src/main/assets/hero_credits.json (title, author, licence, page URL).
"""
import io
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image

UA = "OpenWild/1.0 (https://github.com/zenv99/openwild; free open-source survival plant app)"
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android/app/src/main/res/drawable-nodpi"
CREDITS = ROOT / "android/app/src/main/assets/hero_credits.json"
OK = re.compile(r"^(public domain|pd|cc0|cc by(-sa)? [\d.]+|cc-by(-sa)?-[\d.]+)", re.I)

FILES = {
    "mushroom": "File:Vliegenzwam (Amanita muscaria). Locatie De Famberhorst. 27-09-2020 (d.j.b.).jpg",
    "fruit": "File:ARS tropical fruit no labels.jpg",
}


def get(url):
    for i in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            print("retry", url, e)
            time.sleep(2 * (i + 1))
    raise RuntimeError(url)


def info(title):
    q = urllib.parse.urlencode({"action": "query", "titles": title, "prop": "imageinfo", "format": "json",
                                "iiprop": "url|extmetadata|size", "iiurlwidth": 1600})
    d = json.loads(get(f"https://commons.wikimedia.org/w/api.php?{q}"))
    page = next(iter(d["query"]["pages"].values()))
    ii = page["imageinfo"][0]
    md = ii["extmetadata"]
    strip = lambda s: re.sub(r"<[^>]+>", "", s or "").strip()
    return {
        "title": title, "url": ii.get("thumburl") or ii["url"], "page": ii["descriptionurl"],
        "license": strip(md.get("LicenseShortName", {}).get("value")),
        "author": strip(md.get("Artist", {}).get("value")),
        "width": ii["width"], "height": ii["height"],
    }


CANDIDATES = [
    "File:Fly Agaric mushroom 04.jpg",
    "File:Fliegenpilz fly agaric Amanita muscaria.JPG",
    "File:Amanita muscaria qtl4.jpg",
    "File:Vliegenzwam (Amanita muscaria). Locatie De Famberhorst. 27-09-2020 (d.j.b.).jpg",
    "File:AD2009Sep20 Amanita muscaria 01.jpg",
    "File:Fly Agaric.JPG",
]


def candidates():
    """Preview mode: small copies of several candidates (with licences) for picking by eye."""
    out = ROOT / "tools/hero_candidates"
    out.mkdir(parents=True, exist_ok=True)
    seen = []
    for i, title in enumerate(CANDIDATES):
        try:
            meta = info(title)
        except Exception as e:  # noqa: BLE001
            print("skip", title, e)
            continue
        ok = bool(OK.match(meta["license"]))
        print(i, ok, meta["license"], "|", meta["author"][:60], "|", title)
        if not ok:
            continue
        im = Image.open(io.BytesIO(get(meta["url"]))).convert("RGB")
        im.thumbnail((640, 640))
        im.save(out / f"c{i}.jpg", quality=80)
        seen.append({"i": i, **{k: meta[k] for k in ("title", "author", "license", "page")}})
    (out / "candidates.json").write_text(json.dumps(seen, indent=1, ensure_ascii=False))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    credits = {}
    for key, title in FILES.items():
        meta = info(title)
        print(key, meta["license"], meta["author"], meta["width"], "x", meta["height"])
        if not OK.match(meta["license"]):
            sys.exit(f"Refusing {title}: licence {meta['license']!r} is not public domain / CC0 / CC BY / CC BY-SA")
        im = Image.open(io.BytesIO(get(meta["url"]))).convert("RGB")
        im.thumbnail((1440, 1440), Image.LANCZOS)
        im.save(OUT / f"hero_{key}.webp", "WEBP", quality=82, method=6)
        print(key, im.size, (OUT / f"hero_{key}.webp").stat().st_size // 1024, "KB")
        credits[key] = {k: meta[k] for k in ("title", "author", "license", "page")}
    CREDITS.write_text(json.dumps(credits, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    candidates() if "--candidates" in sys.argv else main()
