"""Commercial-ok reference photos for a region pack.

Display thumbs and on-device fingerprints are built only from CC0, CC BY, and CC BY-SA
images. CC BY-NC and ND are refused. iNaturalist-hosted files are refused as well:
iNaturalist's dataset terms restrict training, separate from the per-photo Creative
Commons licence. Plants For A Future and tropical.theferns.info are never requested.

Life stage comes from the source (Commons category title, file title, or GBIF
lifeStage / reproductiveCondition). A photo is never labelled flowering or fruiting
just because we hope it is. Missing stages stay missing.
"""
from __future__ import annotations

import csv
import hashlib
import html
import json
import re
import threading
import urllib.parse
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

from . import config
from .http import USER_AGENT, get_json

# GBIF dataset: iNaturalist research-grade observations. Not used for training or thumbs.
INAT_DATASET = "50c9509d-22c7-4a22-a47d-8c48425ef4a7"

STAGES = ("seedling", "leaves", "flower", "fruit", "closeup")
STAGE_WORDS = (
    ("seedling", ("seedling", "seedlings", "juvenile", "sapling", "germinat")),
    ("closeup", ("bark", "close-up", "closeup", "close up")),
    ("flower", ("flower", "flowers", "blossom", "inflorescence", "bloom")),
    ("fruit", ("fruit", "fruits", "seed", "seeds", "berry", "berries", "pod", "cone")),
    ("leaves", ("leaf", "leaves", "foliage")),
)
SKIP_TITLE = (
    "distribution", "range map", "locator", "icon", "logo", ".svg", "diagram", "chart",
    "stamp", "coin", "flag of", "coat of arms", "cuisine", "recipe", "map of",
    "question book", "edit-clear", " in art", ".ogg", ".pdf", "audio", "diseas", "disorder",
    "cultivar", "bonsai",
)


def stage_from_text(text: str | None) -> str | None:
    """Map a title or GBIF life-stage string to a stage. Seedling is tested before 'seed'."""
    if not text:
        return None
    low = text.lower()
    for stage, words in STAGE_WORDS:
        if any(w in low for w in words):
            return stage
    return None


def is_inaturalist(*parts: str | None) -> bool:
    blob = " ".join(p for p in parts if p).lower()
    return "inaturalist" in blob


def skip_title(title: str) -> bool:
    low = title.lower()
    if low.endswith((".svg", ".ogg", ".oga", ".pdf", ".gif", ".tif", ".tiff", ".webp")) and "photo" not in low:
        # webp/tiff can still be photos; svg/pdf/audio cannot. gif is almost never a field photo.
        if low.endswith((".svg", ".ogg", ".oga", ".pdf", ".gif")):
            return True
    return any(bad in low for bad in SKIP_TITLE)


def stage_from_category(title: str, scientific: str) -> str | None:
    low = title.lower()
    genus = scientific.split()[0].lower() if scientific else ""
    if scientific.lower() not in low and genus not in low:
        return None
    if skip_title(title):
        return None
    return stage_from_text(low)


def strip_html(value: str | None) -> str:
    text = re.sub(r"<[^>]+>", " ", value or "")
    text = html.unescape(text).replace("\xa0", " ")
    return re.sub(r"\s+", " ", text).strip()[:200]


def _chunks(items: list, n: int):
    for i in range(0, len(items), n):
        yield items[i : i + n]


def wikidata_lookup(names: list[str], keys: dict[str, str] | None = None) -> dict[str, dict]:
    """scientific name -> {cat, images[]} from Wikidata P373 / P18.
    Matches on the taxon name (P225) and, when given, the GBIF taxon key (P846), so a species listed
    under another name on Wikidata still finds its item."""
    found: dict[str, dict] = {}
    keys = keys or {}
    by_key = {k: n for n, k in keys.items() if k}

    def add(name, row):
        slot = found.setdefault(name, {"cat": None, "images": []})
        cat = row.get("cat", {}).get("value")
        if cat and not slot["cat"]:
            slot["cat"] = cat
        image = row.get("image", {}).get("value")
        if image and image not in slot["images"]:
            slot["images"].append(image)

    for chunk in _chunks(names, 40):
        values = " ".join('"' + n.replace("\\", "\\\\").replace('"', "") + '"' for n in chunk)
        kvals = " ".join(f'"{keys[n]}"' for n in chunk if keys.get(n))
        query = (
            "SELECT ?name ?key ?image ?cat WHERE { { VALUES ?name { " + values + " } ?t wdt:P225 ?name. } "
            + (f"UNION {{ VALUES ?key {{ {kvals} }} ?t wdt:P846 ?key. }} " if kvals else "")
            + "OPTIONAL { ?t wdt:P373 ?cat } OPTIONAL { ?t wdt:P18 ?image } }"
        )
        try:
            data = get_json("https://query.wikidata.org/sparql", {"format": "json", "query": query})
        except Exception as e:  # noqa: BLE001
            print(f"wikidata batch failed: {e!r}", flush=True)
            continue
        for row in data.get("results", {}).get("bindings", []):
            name = row.get("name", {}).get("value") or by_key.get(row.get("key", {}).get("value", ""))
            if name:
                add(name, row)
    return found


def _commons_api(params: dict) -> dict:
    return get_json("https://commons.wikimedia.org/w/api.php", {"format": "json", **params}, retries=2)


def category_members(title: str, cmtype: str, limit: int) -> list[str]:
    cmtitle = title if title.startswith("Category:") else f"Category:{title}"
    out: list[str] = []
    cont: dict = {}
    while len(out) < limit:
        try:
            data = _commons_api({
                "action": "query",
                "list": "categorymembers",
                "cmtitle": cmtitle,
                "cmtype": cmtype,
                "cmlimit": str(min(50, limit - len(out))),
                **cont,
            })
        except Exception as e:  # noqa: BLE001
            print(f"commons category failed {cmtitle}: {e!r}", flush=True)
            break
        members = data.get("query", {}).get("categorymembers", [])
        out.extend(m["title"] for m in members if m.get("title"))
        token = (data.get("continue") or {}).get("cmcontinue")
        if not token or not members:
            break
        cont = {"cmcontinue": token}
    return out[:limit]


def other_organism(title: str, scientific: str) -> bool:
    """True when the filename names a different genus (a dragonfly filed under a plant, for example)."""
    genus = scientific.split()[0].lower() if scientific else ""
    for other, _epithet in re.findall(r"([A-Z][a-z]{2,})[ _]([a-z]{3,})", title):
        if other.lower() != genus:
            return True
    return False


def image_records(titled: list[tuple[str, str]], scientific: str) -> list[dict]:
    """(commons title, suggested stage) -> candidate dicts with a commercial-ok licence."""
    out: list[dict] = []
    titled = [(t, s) for t, s in titled if not other_organism(t, scientific)]
    by_title = {t: stage for t, stage in titled if "|" not in t}
    titles = list(by_title)
    for chunk in _chunks(titles, 20):
        try:
            data = _commons_api({
                "action": "query",
                "titles": "|".join(chunk),
                "prop": "imageinfo",
                "iiprop": "url|extmetadata|mime|size",
                "iiurlwidth": "640",
            })
        except Exception as e:  # noqa: BLE001
            print(f"imageinfo failed: {e!r}", flush=True)
            continue
        for page in (data.get("query") or {}).get("pages", {}).values():
            if page.get("missing") is not None:
                continue
            title = page.get("title") or ""
            if skip_title(title):
                continue
            info = (page.get("imageinfo") or [None])[0] or {}
            mime = (info.get("mime") or "").lower()
            if mime not in ("image/jpeg", "image/png", "image/webp", "image/tiff"):
                continue
            width = int(info.get("width") or 0)
            if 0 < width < 180:
                continue
            ext = info.get("extmetadata") or {}

            def meta(key: str) -> str:
                raw = ext.get(key) or {}
                if isinstance(raw, dict):
                    return str(raw.get("value") or "")
                return str(raw or "")

            if is_inaturalist(info.get("url"), info.get("descriptionurl"), meta("Credit")):
                continue
            lic = config.commercial_license(meta("LicenseShortName")) or config.commercial_license(meta("LicenseUrl"))
            if lic is None:
                continue
            url = info.get("thumburl") or info.get("url")
            if not url or is_inaturalist(url):
                continue
            stage = by_title.get(title) or stage_from_text(title) or "field"
            out.append({
                "stage": stage,
                "license": lic,
                "observer": strip_html(meta("Artist")) or strip_html(meta("Credit")),
                "page": info.get("descriptionurl") or "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")),
                "url": url,
                "source": "wikimedia",
            })
    return out


def from_commons_category(cat: str, scientific: str) -> list[dict]:
    titled: list[tuple[str, str]] = []
    for sub in category_members(cat, "subcat", limit=40):
        stage = stage_from_category(sub, scientific)
        if stage is None:
            continue
        for title in category_members(sub, "file", limit=6):
            titled.append((title, stage))
    for title in category_members(cat, "file", limit=12):
        if skip_title(title):
            continue
        titled.append((title, stage_from_text(title) or "field"))
    # P18-style File: titles are handled by the caller.
    genus = scientific.split()[0].lower()
    epithet = scientific.split()[-1].lower() if " " in scientific else ""
    named = []
    for title, stage in titled:
        low = title.lower()
        # Files dropped straight in the parent category must name this plant.
        # Stage subcategories are already specific; a loose "field" file is not.
        if stage == "field" and genus not in low and epithet not in low:
            continue
        named.append((title, stage))
    return image_records(named, scientific)


def from_p18(urls: list[str], scientific: str) -> list[dict]:
    genus = scientific.split()[0].lower()
    titled = []
    for url in urls:
        # http://commons.wikimedia.org/wiki/Special:FilePath/Name.jpg
        name = urllib.parse.unquote(url.rstrip("/").split("/")[-1])
        if not name or skip_title(name):
            continue
        if genus not in name.lower() and scientific.split()[-1].lower() not in name.lower():
            continue  # P18 is sometimes an unrelated lead image
        titled.append((f"File:{name}", stage_from_text(name) or "field"))
    return image_records(titled, scientific)


def from_wikipedia(wiki_url: str, scientific: str) -> list[dict]:
    title = urllib.parse.unquote(wiki_url.rstrip("/").split("/")[-1])
    if not title:
        return []
    try:
        data = get_json("https://en.wikipedia.org/w/api.php", {
            "action": "query", "titles": title, "prop": "images", "imlimit": "30", "format": "json",
        })
    except Exception:
        return []
    pages = (data.get("query") or {}).get("pages", {})
    genus = scientific.split()[0].lower()
    epithet = scientific.split()[-1].lower() if " " in scientific else ""
    titled = []
    for page in pages.values():
        for im in page.get("images") or []:
            name = im.get("title") or ""
            low = name.lower()
            if skip_title(name):
                continue
            if genus not in low and (not epithet or epithet not in low) and stage_from_text(name) is None:
                continue
            titled.append((name, stage_from_text(name) or "field"))
    return image_records(titled, scientific)


def stage_from_gbif(occ: dict) -> str:
    bits = " ".join(str(occ.get(k) or "") for k in ("lifeStage", "reproductiveCondition", "organismRemarks"))
    stage = stage_from_text(bits)
    if stage:
        return stage
    if occ.get("basisOfRecord") == "PRESERVED_SPECIMEN":
        return "specimen"
    return "field"


GBIF_COMMERCIAL = ("CC0_1_0", "CC_BY_4_0")  # occurrence-level licences GBIF can filter on


def from_gbif(taxon_key: int, want: int = 8, pages: int = 3) -> list[dict]:
    """Commercial-ok photos from GBIF (museums, herbaria, Pl@ntNet, Observation.org...), never iNaturalist.
    GBIF filters licences server-side, so each page of 100 is mostly usable instead of mostly iNaturalist."""
    out: list[dict] = []
    for page in range(pages):
        try:
            data = get_json(f"{config.GBIF_API}/occurrence/search", {
                "taxonKey": str(taxon_key), "mediaType": "StillImage", "limit": "100", "offset": str(page * 100),
                "license": list(GBIF_COMMERCIAL),
            }, retries=2)
        except Exception as e:  # noqa: BLE001
            print(f"gbif {taxon_key} failed: {e!r}", flush=True)
            break
        for occ in data.get("results") or []:
            if str(occ.get("datasetKey") or "") == INAT_DATASET:
                continue
            if is_inaturalist(occ.get("references"), occ.get("occurrenceID"), occ.get("institutionCode"), occ.get("publisher")):
                continue
            stage = stage_from_gbif(occ)
            for media in (occ.get("media") or [])[:2]:
                ident = media.get("identifier") or ""
                refs = media.get("references") or ""
                if is_inaturalist(ident, refs, media.get("source"), media.get("creator")):
                    continue
                if (media.get("type") or "StillImage") != "StillImage":
                    continue
                lic = config.commercial_license(media.get("license")) or config.commercial_license(occ.get("license"))
                if lic is None or not ident:
                    continue
                out.append({
                    "stage": stage,
                    "license": lic,
                    "observer": (media.get("rightsHolder") or media.get("creator") or occ.get("recordedBy") or "")[:200],
                    "page": refs or f"https://www.gbif.org/occurrence/{occ.get('key')}",
                    "url": ident,
                    "source": "gbif",
                })
        living = sum(1 for c in out if c["stage"] != "specimen")
        if living >= want or data.get("endOfRecords", True):
            break
    return out


def from_commons_search(scientific: str, limit: int = 20) -> list[dict]:
    """Files on Wikimedia Commons whose title names this species (catches files outside any category)."""
    try:
        data = _commons_api({
            "action": "query", "list": "search", "srnamespace": "6", "srlimit": str(limit),
            "srsearch": f'intitle:"{scientific}"',
        })
    except Exception as e:  # noqa: BLE001
        print(f"commons search failed {scientific}: {e!r}", flush=True)
        return []
    low = scientific.lower()
    titled = []
    for hit in (data.get("query") or {}).get("search", []):
        title = hit.get("title") or ""
        if low not in title.lower().replace("_", " ") or skip_title(title):
            continue
        titled.append((title, stage_from_text(title) or "field"))
    return image_records(titled, scientific)


def _download(url: str, dest: Path) -> bool:
    if dest.exists() and dest.stat().st_size > 2000:
        return True
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=40) as resp:
            data = resp.read(6_000_000)
        if len(data) < 2000:
            return False
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        from PIL import Image
        # Above this, PIL raises instead of decoding a multi-hundred-megapixel scan into RAM.
        Image.MAX_IMAGE_PIXELS = 8_000_000
        try:
            with Image.open(dest) as im:
                im.verify()
            with Image.open(dest) as im:
                if min(im.size) < 160 or im.size[0] * im.size[1] > 24_000_000:
                    dest.unlink(missing_ok=True)
                    return False
        except Exception:
            dest.unlink(missing_ok=True)
            return False
        return True
    except Exception:
        dest.unlink(missing_ok=True)
        return False


def _take(cands: list[dict], img_dir: Path, scientific: str) -> list[dict]:
    by: dict[str, list[dict]] = defaultdict(list)
    seen_url = set()
    for c in cands:
        if c["url"] in seen_url:
            continue
        seen_url.add(c["url"])
        stage = c["stage"] if c["stage"] in (*STAGES, "field", "specimen") else "field"
        by[stage].append({**c, "stage": stage})
    chosen = []
    hashes: set[str] = set()
    order = list(STAGES) + ["field"]
    for stage in order:
        for c in by.get(stage, []):
            digest = hashlib.sha1(c["url"].encode()).hexdigest()[:20]
            dest = img_dir / f"{digest}.img"
            if not _download(c["url"], dest):
                continue
            file_hash = hashlib.sha1(dest.read_bytes()).hexdigest()
            if file_hash in hashes:
                continue
            hashes.add(file_hash)
            chosen.append({**c, "path": str(dest), "scientific_name": scientific})
            break
        if len(chosen) >= 5:
            break
    # One per stage gives variety; if that left fewer than 3, top up from any living-plant photo.
    if len(chosen) < 3:
        for stage in order:
            for c in by.get(stage, []):
                if len(chosen) >= 3:
                    break
                digest = hashlib.sha1(c["url"].encode()).hexdigest()[:20]
                dest = img_dir / f"{digest}.img"
                if any(x["url"] == c["url"] for x in chosen) or not _download(c["url"], dest):
                    continue
                file_hash = hashlib.sha1(dest.read_bytes()).hexdigest()
                if file_hash in hashes:
                    continue
                hashes.add(file_hash)
                chosen.append({**c, "path": str(dest), "scientific_name": scientific})
    if not chosen:
        for c in by.get("specimen", []):
            digest = hashlib.sha1(c["url"].encode()).hexdigest()[:20]
            dest = img_dir / f"{digest}.img"
            if _download(c["url"], dest):
                chosen.append({**c, "path": str(dest), "scientific_name": scientific})
                break
    return chosen


def collect_species(row: dict, wiki: dict, descriptions: dict, img_dir: Path) -> list[dict]:
    """Up to 5 photos per species from every commercial-ok source, best (most stage variety) first:
    Commons category with stage subcategories, the Wikidata lead image, Wikipedia page images,
    a Commons title search, then GBIF (licence-filtered, never iNaturalist)."""
    name = row["scientific_name"]
    cands: list[dict] = []
    cat = (wiki or {}).get("cat")
    if cat:
        cands.extend(from_commons_category(cat, name))
    cands.extend(from_p18((wiki or {}).get("images") or [], name))
    desc = descriptions.get(name.lower()) or {}
    if desc.get("wiki_url") and len(cands) < 3:
        cands.extend(from_wikipedia(desc["wiki_url"], name))
    if len(cands) < 3:
        cands.extend(from_commons_search(name))
    key = row.get("gbif_key") or ""
    if len(cands) < 5 and key.isdigit():
        cands.extend(from_gbif(int(key)))
    return _take(cands, img_dir, name)


def _take_preview(cands: list[dict]) -> set[str]:
    return {c["stage"] for c in cands if c["stage"] in STAGES or c["stage"] == "field"}


def fetch_commercial_photos(region: str, max_species: int | None = None) -> Path:
    d = config.DATA_REGIONS / region
    rows = list(csv.DictReader(open(d / "species.csv", encoding="utf-8")))
    rows.sort(key=lambda r: -float(r.get("gbif_count") or 0))
    if max_species:
        rows = rows[:max_species]
    descriptions = {}
    if (d / "descriptions.csv").exists():
        descriptions = {r["scientific_name"].lower(): r for r in csv.DictReader(open(d / "descriptions.csv", encoding="utf-8"))}
    out_dir = config.WORK / "photos_commercial" / region
    img_dir = out_dir / "img"
    out_dir.mkdir(parents=True, exist_ok=True)
    done_path = out_dir / "done.txt"
    done = set(done_path.read_text(encoding="utf-8").splitlines()) if done_path.exists() else set()
    csv_path = out_dir / "photos.csv"
    fields = ["scientific_name", "path", "license", "observer", "page", "stage", "source"]
    if not csv_path.exists():
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            csv.DictWriter(f, fieldnames=fields).writeheader()
    todo = [r for r in rows if r["scientific_name"] not in done]
    print(f"{region}: {len(rows)} species, {len(done)} already collected, {len(todo)} to go", flush=True)
    lock = threading.Lock()
    finished = {"n": len(done)}

    def persist(name: str, photos: list[dict]) -> None:
        with lock:
            with open(csv_path, "a", newline="", encoding="utf-8") as f:
                w = csv.DictWriter(f, fieldnames=fields)
                for p in photos:
                    w.writerow({k: p.get(k, "") for k in fields})
            if photos:  # species with nothing yet are retried on the next run
                with open(done_path, "a", encoding="utf-8") as f:
                    f.write(name + "\n")
            finished["n"] += 1
            if finished["n"] % 25 == 0:
                print(f"{finished['n']}/{len(rows)} species", flush=True)

    for chunk in _chunks(todo, 40):
        info = wikidata_lookup([r["scientific_name"] for r in chunk],
                               {r["scientific_name"]: (r.get("gbif_key") or "") for r in chunk})

        def one(r, info=info):
            name = r["scientific_name"]
            try:
                photos = collect_species(r, info.get(name) or {}, descriptions, img_dir)
            except Exception as e:  # noqa: BLE001
                print(f"FAIL {name}: {e!r}", flush=True)
                return
            persist(name, photos)

        with ThreadPoolExecutor(max_workers=10) as ex:
            list(ex.map(one, chunk))
    print(f"done {csv_path}", flush=True)
    return csv_path


def build_region_photo_pack(region_id: str, version: int, out: Path | None = None) -> Path:
    """Species list + safety text + light WebP thumbs + one fingerprint per reference photo."""
    import json as _json

    from .content import load_curated
    from .freemodel import embed_tflite
    from .pack import SpeciesRecord, build_pack
    from .regions import get_region

    region = get_region(region_id)
    d = config.DATA_REGIONS / region_id
    photos_path = config.WORK / "photos_commercial" / region_id / "photos.csv"
    photos = list(csv.DictReader(open(photos_path, encoding="utf-8"))) if photos_path.exists() else []
    from .freemodel import preprocess

    usable = []
    for p in photos:
        lic = config.commercial_license(p.get("license"))
        path = Path(p.get("path") or "")
        if lic is None or not path.exists() or is_inaturalist(p.get("page"), p.get("url"), p.get("source")):
            continue
        if p.get("source") == "inaturalist":
            continue
        try:
            preprocess(path)
        except Exception as e:  # noqa: BLE001
            print(f"skip unreadable photo {p.get('scientific_name')}: {e!r}", flush=True)
            continue
        usable.append({**p, "license": lic})
    model = config.ANDROID_ASSETS / "embedder.tflite"
    embs = embed_tflite([Path(p["path"]) for p in usable], model) if usable else np.zeros((0, config.EMBED_DIM), np.float32)
    by: dict[str, list[tuple[dict, np.ndarray]]] = defaultdict(list)
    for p, e in zip(usable, embs):
        by[p["scientific_name"].lower()].append((p, e))

    descs = {}
    if (d / "descriptions.csv").exists():
        descs = {r["scientific_name"].lower(): r for r in csv.DictReader(open(d / "descriptions.csv", encoding="utf-8"))}
    recs = []
    for r in csv.DictReader(open(d / "species.csv", encoding="utf-8")):
        key = r["scientific_name"].lower()
        pairs = by.get(key, [])
        # Prefer living-plant stages over a herbarium sheet when both exist.
        living = [pair for pair in pairs if pair[0].get("stage") != "specimen"]
        pairs = living or pairs
        dsc = descs.get(key, {})
        names = {}
        if r.get("common_names"):
            try:
                names = _json.loads(r["common_names"])
            except _json.JSONDecodeError:
                names = {}
        recs.append(SpeciesRecord(
            scientific_name=r["scientific_name"],
            kingdom=r.get("kingdom") or "Plantae",
            gbif_key=int(r["gbif_key"]) if (r.get("gbif_key") or "").isdigit() else None,
            common_name=r.get("common_name") or None,
            common_names=names,
            family=r.get("family") or None,
            genus=r.get("genus") or None,
            obs_count=int(float(r.get("gbif_count") or 0)),
            description=dsc.get("description") or None,
            wiki_url=dsc.get("wiki_url") or None,
            embeddings=np.stack([e for _, e in pairs]) if pairs else None,
            attributions=[{"observer": p.get("observer"), "license": p["license"], "url": p["page"]} for p, _ in pairs],
            photos=[{
                "path": p["path"], "observer": p.get("observer"), "license": p["license"],
                "url": p["page"], "stage": p.get("stage") or "unspecified",
            } for p, _ in pairs],
        ))
    dest = out or (config.ANDROID_ASSETS / "packs" / f"{region.id}.owpack")
    build_pack(
        dest, region.id, region.name, version, recs, load_curated(), guide_only=True,
        min_photos_for_image_protos=1, photos_per_species=5, photos_per_key_species=5,
        keep_each_prototype=True,
        sources=[
            "GBIF species list for this region (occurrence counts; iNaturalist photos excluded)",
            "Wikimedia Commons files under CC0, CC BY, or CC BY-SA",
            "GBIF occurrence media under CC0, CC BY, or CC BY-SA, excluding iNaturalist",
            "Kew World Checklist of Useful Plant Species (CC BY 4.0)",
            "Wikipedia descriptions (CC BY-SA)",
            "Wikidata (CC0)",
        ],
    )
    _write_catalog(dest, region.id, version)
    stale = dest.parent / f"{region.id}-data-update.owpack"
    if stale.exists() and stale.resolve() != dest.resolve():
        stale.unlink()
    _write_stats(dest, region.id)
    return dest


def _write_catalog(pack: Path, region_id: str, version: int) -> None:
    import sqlite3
    con = sqlite3.connect(pack)
    species = con.execute("SELECT COUNT(*) FROM species").fetchone()[0]
    con.close()
    catalog_path = pack.parent / "catalog.json"
    cur = json.loads(catalog_path.read_text()) if catalog_path.exists() else []
    cur = [e for e in cur if e.get("region_id") != region_id]
    cur.append({
        "region_id": region_id,
        "version": version,
        "file": pack.name,
        "species_count": species,
        "complete": True,
    })
    catalog_path.write_text(json.dumps(cur, indent=2) + "\n")


def _write_stats(pack: Path, region_id: str) -> None:
    import sqlite3
    con = sqlite3.connect(pack)
    species = con.execute("SELECT COUNT(*) FROM species").fetchone()[0]
    photo_n = con.execute("SELECT COUNT(*) FROM photo").fetchone()[0]
    photo_sp = con.execute("SELECT COUNT(DISTINCT species_id) FROM photo").fetchone()[0]
    proto_n = con.execute("SELECT COUNT(*) FROM prototype").fetchone()[0]
    proto_sp = con.execute("SELECT COUNT(DISTINCT species_id) FROM prototype").fetchone()[0]
    licenses = dict(con.execute("SELECT license, COUNT(*) FROM photo GROUP BY license"))
    stages = dict(con.execute("SELECT stage, COUNT(*) FROM photo GROUP BY stage"))
    lengths = [r[0] for r in con.execute("SELECT length(image) FROM photo")]
    con.close()
    model = config.ANDROID_ASSETS / "embedder.tflite"
    stats = {
        "region": region_id,
        "pack_bytes": pack.stat().st_size,
        "model_bytes": model.stat().st_size if model.exists() else 0,
        "model_id": config.MODEL_ID,
        "species": species,
        "display_photos": photo_n,
        "species_with_display_photo": photo_sp,
        "prototypes": proto_n,
        "species_with_prototype": proto_sp,
        "licenses": licenses,
        "stages": stages,
        "display_long_edge_px": "320, or 256 if the WebP would exceed 20KB",
        "display_webp_quality": "65, or 60 when needed to stay near 8–20KB",
        "mean_image_bytes": int(sum(lengths) / len(lengths)) if lengths else 0,
        "max_image_bytes": max(lengths) if lengths else 0,
        "licenses_allowed": sorted(config.COMMERCIAL_LICENSES),
        "excluded": ["CC BY-NC", "CC BY-ND", "iNaturalist dataset and inaturalist.org files", "PFAF images", "tropical.theferns.info"],
    }
    path = config.DATA_REGIONS / region_id / "photo_pack_stats.json"
    path.write_text(json.dumps(stats, indent=2) + "\n")
    print(json.dumps(stats, indent=2), flush=True)
