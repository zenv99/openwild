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
    """Map a title or GBIF life-stage string to a stage. Seedling is tested before 'seed'.
    iNaturalist's 'no evidence of flowering' means a vegetative plant: leaves, not flower."""
    if not text:
        return None
    low = text.lower()
    if "no evidence" in low or "vegetative" in low or "sterile" in low:
        return "leaves"
    for stage, words in STAGE_WORDS:
        if any(w in low for w in words):
            return stage
    return None


# Substrings that always mean a specimen sheet or an illustration...
SHEET_SUBSTR = ("herbarium", "herbier", "specimen", "exsiccat", "isotype", "holotype", "lectotype", "syntype",
                "illustration", "botanical_magazine", "flora_de_filipinas", "medizinal-pflanzen")
# ...and whole words that do (matched as words, so "plateau" is not "plate").
SHEET_WORDS = {"drawing", "plate", "tafel", "planche", "lamina", "blanco", "hortus", "kohler", "köhler", "curtis"}


_SHEET_MEMO: dict[str, float] = {}


def sheet_fraction(path) -> float:
    """Share of the picture that is near-white, unsaturated paper (0..1). Decodes JPEGs at 1/8 size
    (draft mode) and remembers results, so checking tens of thousands of photos takes minutes, not hours."""
    import numpy as np
    from PIL import Image

    key = str(path)
    if key in _SHEET_MEMO:
        return _SHEET_MEMO[key]
    disk = _sheet_disk(Path(path).parent.parent)
    if key in disk:
        _SHEET_MEMO[key] = disk[key]
        return disk[key]
    with Image.open(path) as im:
        im.draft("RGB", (128, 128))
        a = np.asarray(im.convert("RGB").resize((96, 96))).astype(np.float32)
    mx, mn = a.max(2), a.min(2)
    v = float(((mx > 190) & ((mx - mn) / (mx + 1e-6) < 0.12)).mean())
    _SHEET_MEMO[key] = v
    disk[key] = v
    return v


_SHEET_DISK: dict[str, dict[str, float]] = {}


def _sheet_disk(folder: Path) -> dict[str, float]:
    """Whiteness results kept next to the photos (sheet_cache.json), shared by every pack build."""
    import json

    k = str(folder)
    if k not in _SHEET_DISK:
        f = folder / "sheet_cache.json"
        try:
            _SHEET_DISK[k] = json.loads(f.read_text()) if f.exists() else {}
        except Exception:  # noqa: BLE001
            _SHEET_DISK[k] = {}
    return _SHEET_DISK[k]


def save_sheet_cache() -> None:
    import json

    for k, d in _SHEET_DISK.items():
        if d:
            try:
                (Path(k) / "sheet_cache.json").write_text(json.dumps(d))
            except Exception:  # noqa: BLE001
                pass


def looks_like_sheet(path=None, *texts: str | None, threshold: float = 0.40) -> bool:
    """Herbarium sheets, label pages and old botanical drawings on white paper: never shown or used.
    Judged by the source title/URL and by how much of the picture is plain white paper."""
    import re

    blob = " ".join(t for t in texts if t).lower().replace("%20", "_").replace(" ", "_")
    if any(w in blob for w in SHEET_SUBSTR) or SHEET_WORDS & set(re.split(r"[^a-zà-ÿ]+", blob)):
        return True
    if path:
        try:
            return sheet_fraction(path) > threshold
        except Exception:  # noqa: BLE001
            return False
    return False


def is_inaturalist(*parts: str | None) -> bool:
    """True only if iNaturalist content is NOT allowed by the current policy and this is iNaturalist."""
    if config.ALLOW_INATURALIST:
        return False
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


_COMMONS_SLOTS = threading.Semaphore(2)  # at most 2 Commons API calls in flight; the rest queue politely


def _commons_api(params: dict) -> dict:
    with _COMMONS_SLOTS:
        return get_json("https://commons.wikimedia.org/w/api.php", {"format": "json", "maxlag": "5", **params},
                        retries=3, timeout=25)


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
            lic = config.photo_license(meta("LicenseShortName")) or config.photo_license(meta("LicenseUrl"))
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


GBIF_COMMERCIAL = ("CC0_1_0", "CC_BY_4_0", "CC_BY_NC_4_0") if config.NONCOMMERCIAL_APP else ("CC0_1_0", "CC_BY_4_0")


def from_gbif(taxon_key: int, want: int = 15, pages: int = 3) -> list[dict]:
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
            if str(occ.get("datasetKey") or "") == INAT_DATASET and not config.ALLOW_INATURALIST:
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
                lic = config.photo_license(media.get("license")) or config.photo_license(occ.get("license"))
                if lic is None or not ident:
                    continue
                if "inaturalist" in ident.lower():
                    from .gbif_photos import smaller_url
                    ident = smaller_url(ident)
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


PLANTNET_ORG = "da86174a-a605-43a4-a5e8-53d484152cd3"   # Pl@ntNet (INRIA / UMR AMAP) publisher on GBIF


def from_plantnet(taxon_key: int, want: int = 20) -> list[dict]:
    """Pl@ntNet's own user photos, published openly on GBIF (confirmed by Pl@ntNet, Nov 2026). Asked for
    directly by publisher: in a plain GBIF search they sit behind millions of iNaturalist records. Many are
    garden plants, houseplants and close-ups of leaves, flowers and fruit, which is what people photograph."""
    out: list[dict] = []
    try:
        data = get_json(f"{config.GBIF_API}/occurrence/search", {
            "taxonKey": str(taxon_key), "mediaType": "StillImage", "publishingOrg": PLANTNET_ORG,
            "limit": str(min(100, want * 2)),
        }, retries=2)
    except Exception as e:  # noqa: BLE001
        print(f"plantnet {taxon_key} failed: {e!r}", flush=True)
        return out
    for occ in data.get("results") or []:
        stage = stage_from_gbif(occ)
        for media in (occ.get("media") or [])[:2]:
            ident = media.get("identifier") or ""
            if (media.get("type") or "StillImage") != "StillImage" or not ident:
                continue
            lic = config.photo_license(media.get("license")) or config.photo_license(occ.get("license"))
            if lic is None:
                continue
            out.append({
                "stage": stage, "license": lic,
                "observer": ((media.get("rightsHolder") or media.get("creator") or occ.get("recordedBy") or "Pl@ntNet user")[:180]
                             + " (Pl@ntNet)"),
                "page": media.get("references") or f"https://www.gbif.org/occurrence/{occ.get('key')}",
                "url": ident, "source": "plantnet",
            })
        if len(out) >= want:
            break
    return out


INAT_API = "https://api.inaturalist.org/v1/observations"
INAT_LICENSES = "cc0,cc-by,cc-by-sa,cc-by-nc,cc-by-nc-sa"
# iNaturalist "Plant Phenology" annotation (attribute 12) -> our growth stages.
INAT_PHENOLOGY = {13: "flower", 15: "flower", 14: "fruit", 21: "leaves"}


def from_inaturalist_garden(scientific: str, want: int = 30) -> list[dict]:
    """Garden, potted and planted specimens of the plant (iNaturalist marks these "captive/cultivated", so
    they never reach research grade). Only observations whose community ID agrees with the species are
    used, so they are as reliable as wild ones. They teach the app what the plant looks like in a pot, a
    garden or a hotel lobby, which is how most people meet it."""
    if not config.ALLOW_INATURALIST:
        return []
    try:
        data = get_json(INAT_API, {
            "taxon_name": scientific, "photos": "true", "photo_license": INAT_LICENSES, "captive": "true",
            "identified": "true", "per_page": str(want), "order_by": "votes",
        }, retries=2, timeout=40)
    except Exception as e:  # noqa: BLE001
        print(f"inat garden {scientific} failed: {e!r}", flush=True)
        return []
    out = []
    want_name = scientific.lower().split()
    for obs in data.get("results") or []:
        taxon = obs.get("taxon") or {}
        if (taxon.get("name") or "").lower().split()[:2] != want_name[:2]:
            continue
        if not obs.get("community_taxon_id") or obs.get("community_taxon_id") != taxon.get("id"):
            continue  # nobody else confirmed it: skip
        user = obs.get("user") or {}
        for ph in (obs.get("photos") or [])[:2]:
            lic = config.photo_license(ph.get("license_code"))
            url = (ph.get("url") or "").replace("/square.", "/medium.")
            if lic is None or not url:
                continue
            out.append({
                "stage": "field", "garden": True, "license": lic,
                "observer": (user.get("name") or user.get("login") or "")[:200],
                "page": f"https://www.inaturalist.org/photos/{ph.get('id')}", "url": url, "source": "inaturalist-garden",
            })
    return out


def from_inaturalist(scientific: str, want: int = 30) -> list[dict]:
    """Open-licence photos from iNaturalist research-grade observations, most-voted first, with growth
    stage from the observers' phenology annotations. Needs-ID observations are not used as a photo of
    this species (a Bali report flagged one). Only used when the app is non-commercial."""
    if not config.ALLOW_INATURALIST:
        return []
    try:
        data = get_json(INAT_API, {
            "taxon_name": scientific, "photos": "true", "photo_license": INAT_LICENSES,
            "quality_grade": "research", "per_page": str(want), "order_by": "votes",
        }, retries=2, timeout=40)
    except Exception as e:  # noqa: BLE001
        print(f"inat {scientific} failed: {e!r}", flush=True)
        return []
    out = []
    want_name = scientific.lower().split()
    for obs in data.get("results") or []:
        tname = ((obs.get("taxon") or {}).get("name") or "").lower().split()
        if tname[:2] != want_name[:2]:
            continue  # a different (e.g. parent) taxon
        stage = "field"
        for a in obs.get("annotations") or []:
            if a.get("controlled_attribute_id") == 12 and a.get("controlled_value_id") in INAT_PHENOLOGY:
                stage = INAT_PHENOLOGY[a["controlled_value_id"]]
        user = obs.get("user") or {}
        for ph in (obs.get("photos") or [])[:3]:
            lic = config.photo_license(ph.get("license_code"))
            url = (ph.get("url") or "").replace("/square.", "/medium.")
            if lic is None or not url:
                continue
            out.append({
                "stage": stage, "license": lic, "observer": (user.get("name") or user.get("login") or "")[:200],
                "page": f"https://www.inaturalist.org/observations/{obs.get('id')}", "url": url, "source": "inaturalist",
            })
    return out


WIKI_LANGS = ("en", "id", "de", "fr", "nl", "es")


def from_wikipedias(scientific: str) -> list[dict]:
    """Lead images of the plant's article on several Wikipedias and Wikispecies. Only files hosted on
    Wikimedia Commons with an open licence are kept (local, non-free images never resolve there)."""
    titled: list[tuple[str, str]] = []
    hosts = [f"https://{lang}.wikipedia.org/w/api.php" for lang in WIKI_LANGS] + ["https://species.wikimedia.org/w/api.php"]
    for api in hosts:
        try:
            data = get_json(api, {"action": "query", "titles": scientific, "redirects": "1", "prop": "pageimages",
                                  "piprop": "name", "format": "json"}, retries=1, timeout=20)
        except Exception:  # noqa: BLE001
            continue
        for page in ((data.get("query") or {}).get("pages") or {}).values():
            f = page.get("pageimage")
            if f and not skip_title(f):
                titled.append((f"File:{f}", stage_from_text(f) or "field"))
    seen = set()
    titled = [t for t in titled if not (t[0] in seen or seen.add(t[0]))]
    return image_records(titled, scientific) if titled else []


def synonyms(scientific: str, key: str | None = None, limit: int = 8) -> list[tuple[str, int]]:
    """Other names for the same plant (accepted name and synonyms from GBIF): photos are often filed
    under an older or newer name."""
    out: list[tuple[str, int]] = []
    try:
        m = get_json("https://api.gbif.org/v1/species/match", {"name": scientific, "kingdom": "Plantae"}, retries=1)
        acc = m.get("acceptedUsageKey") or m.get("usageKey")
        if not acc:
            return []
        if m.get("acceptedUsageKey"):
            a = get_json(f"https://api.gbif.org/v1/species/{acc}", {}, retries=1)
            if a.get("canonicalName") and a["canonicalName"].lower() != scientific.lower():
                out.append((a["canonicalName"], int(acc)))
        syn = get_json(f"https://api.gbif.org/v1/species/{acc}/synonyms", {"limit": "50"}, retries=1)
        for r in syn.get("results") or []:
            n = r.get("canonicalName") or ""
            if n and n.lower() != scientific.lower() and len(n.split()) == 2 and n not in [x[0] for x in out]:
                out.append((n, int(r["key"])))
    except Exception:  # noqa: BLE001
        return out
    return out[:limit]


def from_synonyms(scientific: str, key: str | None = None) -> list[dict]:
    got: list[dict] = []
    for alt, alt_key in synonyms(scientific, key):
        got += from_gbif(alt_key, want=10)
        if len(got) < 6:
            got += from_inaturalist(alt, want=20)
        if len(got) < 3:
            got += from_commons_search(alt)
        if len(got) >= 12:
            break
    return got


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


def _download_scan(url: str, dest: Path, max_side: int = 1600) -> bool:
    """Museum specimen scans are huge (10-60 MB, 50+ megapixels). Fetch the whole file, decode at reduced
    size and save a phone-sized JPEG. Naturalis serves a medium size directly."""
    if dest.exists() and dest.stat().st_size > 2000:
        return True
    import io
    import time
    import urllib.error

    data = b""
    for attempt in range(5):
        # Museums throttle bursts (HTTP 403/429): pace every request and back off on refusals.
        time.sleep(1.2 if attempt == 0 else 4 * attempt)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "image/*"})
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = resp.read(90_000_000)
            break
        except urllib.error.HTTPError as e:
            if e.code not in (403, 429, 500, 502, 503, 504):
                print(f"scan download failed {url[:90]}: {e!r}", flush=True)
                return False
        except Exception:  # noqa: BLE001
            pass
    if len(data) < 2000:
        print(f"scan download failed {url[:90]}: refused or empty after retries", flush=True)
        return False
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None          # trusted museum scan; decoded at reduced size below
        with Image.open(io.BytesIO(data)) as im:
            im.draft("RGB", (max_side, max_side))
            im = im.convert("RGB")
            im.thumbnail((max_side, max_side))
            if min(im.size) < 160:
                return False
            dest.parent.mkdir(parents=True, exist_ok=True)
            im.save(dest, "JPEG", quality=86)
        return True
    except Exception as e:  # noqa: BLE001
        print(f"scan download failed {url[:90]}: {e!r}", flush=True)
        dest.unlink(missing_ok=True)
        return False
    finally:
        from PIL import Image as _I
        _I.MAX_IMAGE_PIXELS = 8_000_000


PER_STAGE = 3        # photos kept per growth stage (seedling, leaves, flower, fruit, close-up)
MAX_PER_SPECIES = 12


MORE_REFS = bool(__import__("os").environ.get("OW_MORE_REFS"))
MORE_TARGET, MORE_TARGET_RARE = 30, 12   # pass 2 (Nov 2026): + Pl@ntNet photos   # reference photos per plant in the "more references" pass
GARDEN_CAP = 6                           # of which garden / potted at most


def _take(cands: list[dict], img_dir: Path, scientific: str, cap: int | None = None) -> list[dict]:
    """Up to PER_STAGE photos for each growth stage, ordered round-robin across stages so the first
    photos show different stages (a light pack that keeps only the first few still gets variety).
    General field photos fill in when stages are missing. Herbarium sheets and drawings are never used."""
    by: dict[str, list[dict]] = defaultdict(list)
    seen_url = set()
    per_stage_cap = PER_STAGE * 2 if cap and cap > MAX_PER_SPECIES else PER_STAGE
    for c in cands:
        if c["url"] in seen_url:
            continue
        seen_url.add(c["url"])
        if c.get("garden"):
            by["garden"].append(c)
            continue
        stage = c["stage"] if c["stage"] in (*STAGES, "field", "specimen") else "field"
        by[stage].append({**c, "stage": stage})
    hashes: set[str] = set()

    def fetch(c) -> dict | None:
        if c.get("stage") == "specimen" or looks_like_sheet(None, c.get("page"), c.get("url")):
            return None
        digest = hashlib.sha1(c["url"].encode()).hexdigest()[:20]
        dest = img_dir / f"{digest}.img"
        if not _download(c["url"], dest):
            return None
        if looks_like_sheet(dest):  # white paper: a herbarium sheet or an old drawing
            dest.unlink(missing_ok=True)
            return None
        file_hash = hashlib.sha1(dest.read_bytes()).hexdigest()
        if file_hash in hashes:
            return None
        hashes.add(file_hash)
        return {**c, "path": str(dest), "scientific_name": scientific}

    per_stage: dict[str, list[dict]] = {}
    for stage in STAGES:
        got = []
        for c in by.get(stage, []):
            if len(got) >= per_stage_cap:
                break
            r = fetch(c)
            if r:
                got.append(r)
        if got:
            per_stage[stage] = got
    # Fill with general field photos: up to PER_STAGE, more if no stage photos were found at all.
    field_cap = per_stage_cap if per_stage else per_stage_cap * 2
    if cap and cap > MAX_PER_SPECIES:
        field_cap = max(field_cap, cap - GARDEN_CAP - sum(len(v) for v in per_stage.values()))
    got = []
    for c in by.get("field", []):
        if len(got) >= field_cap:
            break
        r = fetch(c)
        if r:
            got.append(r)
    if got:
        per_stage["field"] = got
    chosen: list[dict] = []
    for rnd in range(max((len(v) for v in per_stage.values()), default=0)):
        for stage in (*STAGES, "field"):
            lst = per_stage.get(stage, [])
            if rnd < len(lst):
                chosen.append(lst[rnd])
    # Garden / potted photos go last, so a wild photo stays the cover.
    garden = []
    for c in by.get("garden", []):
        if len(garden) >= GARDEN_CAP:
            break
        r = fetch(c)
        if r:
            garden.append(r)
    # Herbarium sheets are never used: a plant with no living photo shows no photo.
    limit = cap or MAX_PER_SPECIES
    return chosen[:max(0, limit - len(garden))] + garden


KEY_TARGET = 12  # photos wanted for each poisonous or edible plant


def genus_relatives(genus: str, exclude: str, folder: Path, want: int = 2, cache_only: bool = False) -> list[dict]:
    """Display photos of the closest relative found ANYWHERE (same genus, best-documented species), for a
    deadly/poisonous plant with no photo of its own and no relative in the region. Open licences only
    (iNaturalist research grade, then GBIF). Always labelled in the app as a related plant, never used for ID.
    Results are cached in folder/relatives.json."""
    cache_f = folder / "relatives.json"
    try:
        cache = json.loads(cache_f.read_text()) if cache_f.exists() else {}
    except Exception:  # noqa: BLE001
        cache = {}
    key = genus.lower()
    if key in cache:
        cands = cache[key]
    elif cache_only:
        return []
    else:
        cands = _genus_candidates(genus)
        cache[key] = cands
        folder.mkdir(parents=True, exist_ok=True)
        cache_f.write_text(json.dumps(cache))
    cands = [c for c in cands if c["species"].lower() != exclude.lower()]
    # One donor species (the best-documented), so the photos are consistent.
    counts: dict[str, int] = defaultdict(int)
    for c in cands:
        counts[c["species"]] += 1
    out: list[dict] = []
    if counts:
        donor = max(counts, key=counts.get)
        for c in [c for c in cands if c["species"] == donor]:
            dest = folder / "relatives" / slug_name(donor) / (hashlib.sha1(c["url"].encode()).hexdigest()[:16] + ".jpg")
            if _download(c["url"], dest) and not looks_like_sheet(dest, c["page"], c["url"]) and not _looks_like_micrograph(dest):
                out.append({**c, "path": str(dest)})
            if len(out) >= want:
                break
    return out


def _looks_like_micrograph(path: Path) -> bool:
    """Microscope shots (spores, cells) on a dark field: no use for recognising a plant or mushroom."""
    try:
        import numpy as np
        from PIL import Image
        with Image.open(path) as im:
            im.draft("RGB", (128, 128))
            v = np.asarray(im.convert("HSV").resize((64, 64)))[..., 2] / 255.0
        border = np.concatenate([v[:6].ravel(), v[-6:].ravel(), v[:, :6].ravel(), v[:, -6:].ravel()])
        return float((border < 0.18).mean()) > 0.6 and float(v.std()) > 0.15
    except Exception:  # noqa: BLE001
        return False


def _genus_candidates(genus: str) -> list[dict]:
    cands: list[dict] = []
    if config.ALLOW_INATURALIST:
        try:
            data = get_json(INAT_API, {
                "taxon_name": genus, "photos": "true", "photo_license": INAT_LICENSES, "quality_grade": "research",
                "per_page": "50", "order_by": "votes",
            }, retries=2, timeout=40)
            for obs in data.get("results") or []:
                t = obs.get("taxon") or {}
                name = t.get("name") or ""
                if t.get("rank") != "species" or name.split()[0].lower() != genus.lower():
                    continue
                user = obs.get("user") or {}
                for ph in (obs.get("photos") or [])[:1]:   # the overview shot, not microscope/detail photos
                    lic = config.photo_license(ph.get("license_code"))
                    url = (ph.get("url") or "").replace("/square.", "/medium.")
                    if lic and url:
                        cands.append({"species": name, "license": lic, "url": url,
                                      "observer": (user.get("name") or user.get("login") or "")[:200],
                                      "page": f"https://www.inaturalist.org/observations/{obs.get('id')}"})
        except Exception as e:  # noqa: BLE001
            print(f"relatives inat {genus} failed: {e!r}", flush=True)
    if not cands:
        try:
            m = get_json(f"{config.GBIF_API}/species/match", {"name": genus, "rank": "GENUS"}, retries=2)
            if m.get("usageKey") and (m.get("rank") or "").upper() == "GENUS":
                for c in from_gbif(int(m["usageKey"]), want=10, pages=2):
                    if c["stage"] != "specimen":
                        cands.append({"species": f"{genus} sp.", "license": c["license"], "url": c["url"],
                                      "observer": c["observer"], "page": c["page"]})
        except Exception as e:  # noqa: BLE001
            print(f"relatives gbif {genus} failed: {e!r}", flush=True)
    return cands


def exact_specimens(scientific: str, folder: Path, want: int = 1, cache_only: bool = False) -> list[dict]:
    """A deadly/poisonous plant nobody has photographed alive: a museum specimen of THIS exact plant (open
    licence, from GBIF; CC0 / CC BY). Shown with a "pressed museum specimen of this exact plant" banner,
    followed by photos of the closest living relative. Never used for photo ID. Cached in folder/specimens.json."""
    cache_f = folder / "specimens.json"
    try:
        cache = json.loads(cache_f.read_text()) if cache_f.exists() else {}
    except Exception:  # noqa: BLE001
        cache = {}
    key = scientific.lower()
    if key not in cache and cache_only:
        return []
    if key not in cache:
        cands: list[dict] = []
        try:
            m = get_json(f"{config.GBIF_API}/species/match", {"name": scientific, "strict": "true"}, retries=2)
            if m.get("usageKey") and (m.get("rank") or "").upper() == "SPECIES":
                d = get_json(f"{config.GBIF_API}/occurrence/search", {
                    "taxonKey": str(m["usageKey"]), "mediaType": "StillImage", "basisOfRecord": "PRESERVED_SPECIMEN",
                    "limit": "100",
                }, retries=6, timeout=90)
                for o in d.get("results") or []:
                    for md in o.get("media") or []:
                        url = md.get("identifier") or ""
                        lic = config.photo_license(md.get("license")) or config.photo_license(o.get("license"))
                        if not url or lic is None or url.endswith("/manifest") or "/300," in url:
                            continue
                        cands.append({"license": lic, "url": url,
                                      "observer": (o.get("institutionCode") or md.get("rightsHolder") or o.get("publisher") or "")[:120],
                                      "page": f"https://www.gbif.org/occurrence/{o.get('key')}"})
        except Exception as e:  # noqa: BLE001
            # A failed lookup is not "no specimen exists": don't remember it, try again next build.
            print(f"specimens {scientific} failed: {e!r}", flush=True)
            return []
        # Open licences only; CC0 first, then CC BY.
        # CC0 first, then CC BY; alternate institutions so one museum's refusal doesn't block the rest.
        cands.sort(key=lambda c: 0 if c["license"] == "cc0" else 1)
        seen_inst: dict[str, int] = defaultdict(int)
        for c in cands:
            seen_inst[c["observer"]] += 1
            c["_rank"] = seen_inst[c["observer"]]
        cands.sort(key=lambda c: (c["_rank"], 0 if c["license"] == "cc0" else 1))
        cache[key] = [{k: v for k, v in c.items() if k != "_rank"} for c in cands[:30]]
        folder.mkdir(parents=True, exist_ok=True)
        cache_f.write_text(json.dumps(cache))
    out = []
    for c in cache[key]:
        dest = folder / "specimens" / slug_name(scientific) / (hashlib.sha1(c["url"].encode()).hexdigest()[:16] + ".jpg")
        if _download_scan(c["url"], dest):
            out.append({**c, "path": str(dest)})
        if len(out) >= want:
            break
    return out


def slug_name(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")


def safety_species(rows: list[dict]) -> set[str]:
    """Species with poison or food information: curated entries, quick facts, and genera with group rules."""
    from .content import load_curated, load_group_rules, load_quick

    names = set(load_curated()) | set(load_quick())
    rules = load_group_rules()
    genera = {t for (rank, t) in rules if rank == "genus"}
    families = {t for (rank, t) in rules if rank == "family"}
    names |= {r["scientific_name"].lower() for r in rows if (r.get("genus") or r["scientific_name"].split()[0]).lower() in genera}
    # Family-level warnings too (e.g. the carrot family, flagged because it contains deadly hemlocks).
    names |= {r["scientific_name"].lower() for r in rows if (r.get("family") or "").lower() in families}
    return names


def more_target(row: dict) -> int:
    return MORE_TARGET if float(row.get("gbif_count") or 0) >= 20 else MORE_TARGET_RARE


def collect_species(row: dict, wiki: dict, descriptions: dict, img_dir: Path, critical: bool = False,
                    skip: set[str] | None = None, have: int = 0) -> list[dict]:
    """Up to 5 photos per species from every commercial-ok source, best (most stage variety) first:
    Commons category with stage subcategories, the Wikidata lead image, Wikipedia page images,
    a Commons title search, then GBIF (licence-filtered, never iNaturalist)."""
    import time as _t

    name = row["scientific_name"]
    cands: list[dict] = []
    timing = {}

    def step(label, fn):
        t0 = _t.monotonic()
        got = fn()
        timing[label] = (round(_t.monotonic() - t0, 1), len(got))
        cands.extend(got)

    def living() -> int:
        return sum(1 for c in cands if c.get("stage") != "specimen")

    # Cheap and reliable first: the Wikidata lead image (one call) and GBIF (licence-filtered).
    step("p18", lambda: from_p18((wiki or {}).get("images") or [], name))
    key = row.get("gbif_key") or ""
    if key.isdigit():
        step("gbif", lambda: from_gbif(int(key)))
        step("plantnet", lambda: from_plantnet(int(key), want=20 if MORE_REFS else 8))
    # iNaturalist directly: huge, staged by observers; for species GBIF left short.
    inat_ok = critical or living() == 0 or __import__("os").environ.get("OW_INAT_FULL", "all") == "all" or \
        bool(__import__("os").environ.get("OW_TOPUP"))
    if MORE_REFS:
        # "More references" pass: many more wild photos plus garden / potted ones, for recognition.
        step("inat", lambda: from_inaturalist(name, want=60))
        step("garden", lambda: from_inaturalist_garden(name, want=30))
    elif inat_ok and living() < (KEY_TARGET if critical else 6):
        step("inat", lambda: from_inaturalist(name, want=60 if critical else 30))
    # Wikimedia Commons is slow and throttled: only walk it for species still short of photos.
    cat = (wiki or {}).get("cat")
    if cat and living() < (KEY_TARGET if critical else 3):
        step("cat", lambda: from_commons_category(cat, name))
    desc = descriptions.get(name.lower()) or {}
    if desc.get("wiki_url") and living() < 1:
        step("wiki", lambda: from_wikipedia(desc["wiki_url"], name))
    if living() < 1:
        step("search", lambda: from_commons_search(name))
    # Poisonous or edible and still nothing: every Wikipedia's lead image, then older/newer names.
    if critical and living() < 1:
        step("wikis", lambda: from_wikipedias(name))
    if critical and living() < 3:
        step("synonyms", lambda: from_synonyms(name, row.get("gbif_key")))
    if skip:
        cands = [c for c in cands if (c.get("page") or "") not in skip]
    t0 = _t.monotonic()
    chosen = _take(cands, img_dir, name, cap=max(0, more_target(row) - have) if MORE_REFS else None)
    timing["download"] = (round(_t.monotonic() - t0, 1), len(chosen))
    if TIMING:
        print(f"TIMING {name}: {timing}", flush=True)
    return chosen


TIMING = bool(__import__("os").environ.get("OW_TIMING"))


def select_photo_batch(
    rows: list[dict], tried: set[str], max_species: int | None, spread: bool = False,
) -> tuple[list[dict], bool]:
    """Next species to photograph.

    Names already attempted (`tried`) are skipped, then at most `max_species` are taken. The second
    value is true when more untried species remain, so the next run continues down the list instead
    of photographing the same head of the list again. Spread samples across the whole list for tests
    and does not resume.
    """
    if max_species and spread:
        step = max(1, len(rows) // max_species)
        return rows[::step][:max_species], False
    pending = [r for r in rows if r["scientific_name"] not in tried]
    if max_species:
        return pending[:max_species], len(pending) > max_species
    return pending, False


def _take_preview(cands: list[dict]) -> set[str]:
    return {c["stage"] for c in cands if c["stage"] in STAGES or c["stage"] == "field"}


def fetch_commercial_photos(region: str, max_species: int | None = None, shard: tuple[int, int] | None = None) -> Path:
    d = config.DATA_REGIONS / region
    rows = list(csv.DictReader(open(d / "species.csv", encoding="utf-8")))
    # Life-or-death plants first: anything with poison or food information (curated, quick facts, risky
    # genera) is collected before everything else and gets more photos; then most-observed first.
    key = safety_species(rows)
    rows.sort(key=lambda r: (r["scientific_name"].lower() not in key, -float(r.get("gbif_count") or 0)))
    if shard:
        i, n = shard
        rows = rows[i::n]  # interleaved, so every shard gets common and rare species alike
    descriptions = {}
    if (d / "descriptions.csv").exists():
        descriptions = {r["scientific_name"].lower(): r for r in csv.DictReader(open(d / "descriptions.csv", encoding="utf-8"))}

    out_dir = config.WORK / "photos_commercial" / region
    img_dir = out_dir / "img"
    out_dir.mkdir(parents=True, exist_ok=True)
    import os as _os
    topup = bool(_os.environ.get("OW_TOPUP"))
    tried_path = out_dir / "tried.txt"
    # A capped run used to repeat the same most-observed species forever. Skip names already attempted
    # and take the next batch; walk-shardN.txt tells the scheduler whether the list continues.
    if not topup:
        tried = set(tried_path.read_text(encoding="utf-8").splitlines()) if tried_path.exists() else set()
        rows, more = select_photo_batch(rows, tried, max_species, spread=bool(_os.environ.get("OW_SPREAD")))
    else:
        more = False
        if max_species and _os.environ.get("OW_SPREAD"):
            rows, _ = select_photo_batch(rows, set(), max_species, spread=True)
    shard_i = shard[0] if shard else 0
    (out_dir / f"walk-shard{shard_i}.txt").write_text("1\n" if more else "0\n")
    done_path = out_dir / "done.txt"
    done = set(done_path.read_text(encoding="utf-8").splitlines()) if done_path.exists() else set()
    csv_path = out_dir / "photos.csv"
    fields = ["scientific_name", "path", "license", "observer", "page", "stage", "source"]
    if not csv_path.exists():
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            csv.DictWriter(f, fieldnames=fields).writeheader()
    have: dict[str, int] = {}
    seen_pages: set[str] = set()
    if topup:
        for r0 in csv.DictReader(open(csv_path, encoding="utf-8")):
            have[r0["scientific_name"]] = have.get(r0["scientific_name"], 0) + 1
            seen_pages.add(r0.get("page") or "")
        tdone_path = out_dir / ("morerefs2_done.txt" if MORE_REFS else "topup_done.txt")
        tdone = set(tdone_path.read_text(encoding="utf-8").splitlines()) if tdone_path.exists() else set()
        if __import__("os").environ.get("OW_TOPUP_FRESH"):  # monthly refresh: look again for new open photos
            tdone = set()
            tdone_path.unlink(missing_ok=True)
        if MORE_REFS:
            todo = [r for r in rows if r["scientific_name"] not in tdone and
                    have.get(r["scientific_name"], 0) < more_target(r)]
        else:
            todo = [r for r in rows if r["scientific_name"] not in tdone and
                    have.get(r["scientific_name"], 0) < (KEY_TARGET if r["scientific_name"].lower() in key else 3)]
        if __import__("os").environ.get("OW_CRITICAL_ONLY"):
            todo = [r for r in todo if r["scientific_name"].lower() in key]
        print(f"TOPUP {region}: {len(todo)} species short of photos "
              f"({sum(r['scientific_name'].lower() in key for r in todo)} safety-critical, target {KEY_TARGET})", flush=True)
    else:
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
            if topup:
                with open(out_dir / ("morerefs2_done.txt" if MORE_REFS else "topup_done.txt"), "a", encoding="utf-8") as f:
                    f.write(name + "\n")
            else:
                # Attempted, even with no usable photo, so the next capped batch moves on.
                # Species that did get photos stay in done.txt and are not collected twice.
                with open(tried_path, "a", encoding="utf-8") as f:
                    f.write(name + "\n")
                if photos:
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
                photos = collect_species(r, info.get(name) or {}, descriptions, img_dir, critical=name.lower() in key,
                                         skip=seen_pages if topup else None, have=have.get(name, 0) if topup else 0)
                if topup:  # keep only photos we don't already have
                    photos = [p for p in photos if (p.get("page") or "") not in seen_pages]
            except Exception as e:  # noqa: BLE001
                print(f"FAIL {name}: {e!r}", flush=True)
                return
            persist(name, photos)

        with ThreadPoolExecutor(max_workers=10) as ex:
            list(ex.map(one, chunk))
    print(f"done {csv_path}", flush=True)
    return csv_path


def build_region_photo_pack(region_id: str, version: int, out: Path | None = None, photos_per_species: int = 5,
                            evaluate_to: Path | None = None) -> Path:
    """Species list + safety text + light WebP thumbs + one fingerprint per reference photo."""
    import json as _json

    from .content import load_curated
    from .freemodel import embed_tflite
    from .pack import SpeciesRecord, build_pack
    from .regions import region_or_pack_meta

    region = region_or_pack_meta(region_id)
    d = config.DATA_REGIONS / region_id
    photos_path = config.WORK / "photos_commercial" / region_id / "photos.csv"
    photos = list(csv.DictReader(open(photos_path, encoding="utf-8"))) if photos_path.exists() else []
    # Community photos (users' own, CC BY 4.0) go first, so they become each plant's cover and lead the gallery.
    from .community import load as load_community
    from .inbox import hidden_photos
    from .regions import merged_donors, union_photo_rows

    community = load_community(region_id)
    hidden = hidden_photos(region_id)
    donors = merged_donors(region_id)
    for donor in donors:
        extra = config.WORK / "photos_commercial" / donor / "photos.csv"
        if extra.exists():
            photos.extend(csv.DictReader(open(extra, encoding="utf-8")))
        community.extend(load_community(donor))
        hidden |= hidden_photos(donor)
    if community:
        print(f"COMMUNITY {len(community)} photos for {len({r['scientific_name'].lower() for r in community})} species", flush=True)
    if donors:
        print(f"MERGED photos from {', '.join(donors)} into {region_id}", flush=True)
    photos = union_photo_rows(community + photos)
    from .danger_photos import load_pinned

    pinned = load_pinned(region_id)
    for donor in donors:
        pinned.extend(load_pinned(donor))
    if pinned:
        print(f"DANGER {len(pinned)} pinned deadly/toxic photos", flush=True)
        photos = union_photo_rows(pinned + photos)
    # Photos people in the app said show the wrong plant (weekly feedback) are left out.
    if hidden:
        before = len(photos)
        photos = [p for p in photos if (p.get("page") or "") not in hidden and (p.get("url") or "") not in hidden]
        print(f"HIDDEN {before - len(photos)} photos people marked as the wrong plant", flush=True)
    from .freemodel import preprocess

    usable = []
    sheets = 0
    sheet_paths: list[Path] = []
    for p in photos:
        lic = config.photo_license(p.get("license"))
        path = Path(p.get("path") or "")
        if lic is None or not path.exists() or is_inaturalist(p.get("page"), p.get("url"), p.get("source")):
            continue
        if p.get("source") == "inaturalist" and not config.ALLOW_INATURALIST:
            continue
        if p.get("stage") == "specimen" or looks_like_sheet(path, p.get("page"), p.get("url")):
            sheets += 1
            sheet_paths.append(path)
            continue
        try:
            preprocess(path)
        except Exception as e:  # noqa: BLE001
            print(f"skip unreadable photo {p.get('scientific_name')}: {e!r}", flush=True)
            continue
        usable.append({**p, "license": lic})
    save_sheet_cache()
    print(f"SHEETS removed {sheets} herbarium sheets / drawings on white paper", flush=True)
    model = config.ANDROID_ASSETS / "embedder.tflite"
    embs = _cached_embeddings([Path(p["path"]) for p in usable], model, photos_path.parent / "emb_cache.npz")
    # Second pass: sheets on beige/aged paper, label pages and drawings that the white-paper check misses.
    # Learned from the fingerprints of the sheets already caught above.
    if len(sheet_paths) >= 50 and len(usable):
        readable = []
        for sp_ in sheet_paths[:4000]:
            try:
                preprocess(sp_)
                readable.append(sp_)
            except Exception:  # noqa: BLE001  (truncated or broken file: not useful as an example)
                pass
        ref = _cached_embeddings(readable, model, photos_path.parent / "emb_cache.npz")
        flag = sheet_like(embs, ref)
        if flag.any():
            print(f"SHEETS2 removed {int(flag.sum())} more sheet-like photos (beige paper, labels, drawings)", flush=True)
            for p in [p for p, f in zip(usable, flag) if f][:40]:
                print(f"SHEETS2 {p['scientific_name']} | {p.get('page') or p.get('url')}", flush=True)
            usable = [p for p, f in zip(usable, flag) if not f]
            embs = embs[~flag]
    by: dict[str, list[tuple[dict, np.ndarray]]] = defaultdict(list)
    for p, e in zip(usable, embs):
        by[p["scientific_name"].lower()].append((p, e))
    if evaluate_to is not None and len(usable):
        from .evaluate import leave_one_out, write_report
        write_report(leave_one_out([p["scientific_name"] for p in usable], embs), evaluate_to, region_id)

    descs = {}
    if (d / "descriptions.csv").exists():
        descs = {r["scientific_name"].lower(): r for r in csv.DictReader(open(d / "descriptions.csv", encoding="utf-8"))}
    # A poisonous or edible plant with no photo of its own gets display-only photos of its closest relative
    # (same genus, most photos), clearly labelled "related species". Never used for photo ID.
    species_rows = list(csv.DictReader(open(d / "species.csv", encoding="utf-8")))
    from .cultivated import cultivated_species_rows
    from .regions import merged_donors, union_species_rows
    # Cultivated and introduced names (houseplants, garden plants, fungi) that the wild list missed.
    species_rows = union_species_rows(species_rows, cultivated_species_rows(region_id))
    for donor in merged_donors(region_id):
        extra = config.DATA_REGIONS / donor / "species.csv"
        if extra.exists():
            species_rows = union_species_rows(species_rows, list(csv.DictReader(open(extra, encoding="utf-8"))))
        ddesc = config.DATA_REGIONS / donor / "descriptions.csv"
        if ddesc.exists():
            for row in csv.DictReader(open(ddesc, encoding="utf-8")):
                descs.setdefault((row.get("scientific_name") or "").lower(), row)
    critical = safety_species(species_rows)
    by_genus: dict[str, list[str]] = defaultdict(list)
    for k, v in by.items():
        by_genus[k.split()[0]].append(k)
    related = 0
    recs = []
    # Every name people use for each plant (local tongues first), from Wikidata on top of GBIF's.
    from . import names as _names
    try:
        wd_names = _names.region_names(region_id, [r["scientific_name"] for r in species_rows])
    except Exception as e:  # noqa: BLE001
        print(f"NAMES failed: {e!r}", flush=True)
        wd_names = {}
    local_langs = _names.local_languages(region_id)
    # Online look-ups for plants without a photo (museum specimens, relatives found worldwide) are slow and
    # paced. They get a time budget so the build always finishes and publishes; results are cached in the
    # photo store, so every later build continues where this one stopped. Deadly/poisonous plants go first.
    import os as _os
    import time as _time
    fb_deadline = _time.monotonic() + float(_os.environ.get("OW_FALLBACK_MINUTES", "100")) * 60
    folder = photos_path.parent

    def _fungus(r):
        return (r.get("kingdom") or "").lower() == "fungi" or str(r.get("is_fungus", "")).lower() in ("1", "true")

    def _no_kin(key):
        return not [k for k in by_genus.get(key.split()[0], []) if k != key]

    warm = 0
    for r in species_rows:
        key = r["scientific_name"].lower()
        if key in critical and not by.get(key):
            if _time.monotonic() > fb_deadline:
                break
            if not _fungus(r):
                exact_specimens(r["scientific_name"], folder)
            if _no_kin(key):
                genus_relatives(key.split()[0], r["scientific_name"], folder)
            warm += 1
    print(f"FALLBACK deadly/poisonous plants looked up first: {warm}", flush=True)
    fb_skipped = 0
    for r in species_rows:
        key = r["scientific_name"].lower()
        pairs = by.get(key, [])
        borrowed: list[dict] = []
        cache_only = _time.monotonic() > fb_deadline
        if cache_only and not pairs:
            fb_skipped += 1
        if not pairs:
            # Deadly/poisonous and never photographed alive: the exact plant as a museum specimen first.
            fungus = (r.get("kingdom") or "").lower() == "fungi" or str(r.get("is_fungus", "")).lower() in ("1", "true")
            exact = exact_specimens(r["scientific_name"], photos_path.parent, cache_only=cache_only) if key in critical and not fungus else []
            if exact:
                print(f"EXACT-SPECIMEN {r['scientific_name']} ({exact[0]['observer']})", flush=True)
            kin = sorted((k for k in by_genus.get(key.split()[0], []) if k != key), key=lambda k: -len(by[k]))
            if kin:
                donor = kin[0]
                borrowed = [{
                    "path": p["path"], "license": p["license"], "url": p["page"], "stage": "related",
                    "observer": f"{p.get('observer') or 'unknown'} — related species: {p['scientific_name']}",
                } for p, _ in by[donor][:2]]
                related += 1
            else:
                # No relative in the region: the closest relative found anywhere (open licence, labelled).
                far = genus_relatives(key.split()[0], r["scientific_name"], photos_path.parent, cache_only=cache_only)
                if far:
                    borrowed = [{
                        "path": c["path"], "license": c["license"], "url": c["page"], "stage": "related",
                        "observer": f"{c.get('observer') or 'unknown'} — related species: {c['species']}",
                    } for c in far]
                    related += 1
                    print(f"RELATED-WORLD {r['scientific_name']} <- {far[0]['species']} ({len(far)} photos)", flush=True)
            borrowed = [{
                "path": c["path"], "license": c["license"], "url": c["page"], "stage": "exact_specimen",
                "observer": f"{c.get('observer') or 'museum'} — pressed museum specimen of this exact plant",
            } for c in exact] + borrowed
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
        names = _names.merge(names, wd_names.get(key, {}))
        recs.append(SpeciesRecord(
            scientific_name=r["scientific_name"],
            kingdom=r.get("kingdom") or "Plantae",
            gbif_key=int(r["gbif_key"]) if (r.get("gbif_key") or "").isdigit() else None,
            common_name=r.get("common_name") or _names.best_common_name(names, local_langs) or None,
            common_names=names,
            family=r.get("family") or None,
            genus=r.get("genus") or None,
            obs_count=int(float(r.get("gbif_count") or 0)),
            description=dsc.get("description") or None,
            wiki_url=dsc.get("wiki_url") or None,
            embeddings=np.stack([e for _, e in pairs]) if pairs else None,
            attributions=[{"observer": p.get("observer"), "license": p["license"], "url": p["page"]} for p, _ in pairs] + [{"observer": b["observer"], "license": b["license"], "url": b["url"]} for b in borrowed],
            photos=[{
                "path": p["path"], "observer": p.get("observer"), "license": p["license"],
                "url": p["page"], "stage": p.get("stage") or "unspecified",
            } for p, _ in pairs] + borrowed,
        ))
    print(f"RELATED {related} plants without their own photo show a related species (labelled, never used for ID)", flush=True)
    if fb_skipped:
        print(f"FALLBACK time budget reached: {fb_skipped} plants without a photo wait for the next build", flush=True)
    dest = out or (config.ANDROID_ASSETS / "packs" / f"{region.id}.owpack")
    build_pack(
        dest, region.id, region.name, version, recs, load_curated(), guide_only=True,
        min_photos_for_image_protos=1, photos_per_species=photos_per_species, photos_per_key_species=photos_per_species,
        keep_each_prototype=True,
        sources=[
            "OpenWild community photographers (their own photos, CC BY 4.0)",
            "GBIF species list for this region (occurrence counts)",
            "Wikimedia Commons files under " + ", ".join(sorted(config.PHOTO_LICENSES)),
            "GBIF occurrence media (including iNaturalist observers' photos) under " + ", ".join(sorted(config.PHOTO_LICENSES)),
            "Kew World Checklist of Useful Plant Species (CC BY 4.0)",
            "Wikipedia descriptions (CC BY-SA)",
            "Wikidata (CC0)",
        ],
    )
    _write_refpages(dest, by)
    _write_catalog(dest, region.id, version)
    stale = dest.parent / f"{region.id}-data-update.owpack"
    if stale.exists() and stale.resolve() != dest.resolve():
        stale.unlink()
    _write_stats(dest, region.id)
    return dest


REF_PAGES = 30   # reference photos per plant that Better recognition fingerprints (only 12 are shipped as pictures)


def _write_refpages(pack: Path, by: dict) -> None:
    """Index of every good reference photo per plant (page URLs only, no images). The BioCLIP 2 boost
    fingerprints these, so recognition learns from more photos than the app ships to show."""
    import sqlite3
    con = sqlite3.connect(pack)
    con.execute("CREATE TABLE IF NOT EXISTS refpage (species_id INTEGER NOT NULL, page TEXT NOT NULL)")
    con.execute("DELETE FROM refpage")
    n = 0
    for sid, name in con.execute("SELECT id, scientific_name FROM species").fetchall():
        pages, seen = [], set()
        for p, _ in by.get(name.lower(), []):
            u = p.get("page") or p.get("url")
            if u and u not in seen and (p.get("stage") or "") not in ("related", "specimen", "exact_specimen"):
                seen.add(u)
                pages.append(u)
        for u in pages[:REF_PAGES]:
            con.execute("INSERT INTO refpage VALUES (?,?)", (sid, u))
            n += 1
    con.commit()
    con.close()
    print(f"REFPAGES {n} reference photos indexed for Better recognition", flush=True)


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
        "licenses_allowed": sorted(config.PHOTO_LICENSES),
        "excluded": ["CC BY-NC", "CC BY-ND", "iNaturalist dataset and inaturalist.org files", "PFAF images", "tropical.theferns.info"],
    }
    path = config.DATA_REGIONS / region_id / "photo_pack_stats.json"
    path.write_text(json.dumps(stats, indent=2) + "\n")
    print(json.dumps(stats, indent=2), flush=True)


def sheet_like(embs: np.ndarray, sheets: np.ndarray, threshold: float = 0.5, rounds: int = 2) -> np.ndarray:
    """True for photos whose fingerprint looks like a herbarium sheet / label page / drawing.

    A linear classifier (logistic regression, plain numpy) separating known sheets from everything else,
    retrained once with its own confident finds added, so aged-paper sheets that sit among the "good"
    photos get pulled over too. Conservative: needs probability > threshold AND to be closer to the sheet
    centroid than to the photo centroid."""
    def norm(a):
        return a / (np.linalg.norm(a, axis=1, keepdims=True) + 1e-8)

    x_all, s = norm(embs.astype(np.float32)), norm(sheets.astype(np.float32))
    pos = s
    flag = np.zeros(len(x_all), bool)
    for _ in range(rounds):
        neg = x_all[~flag]
        x = np.concatenate([pos, neg])
        y = np.concatenate([np.ones(len(pos)), np.zeros(len(neg))]).astype(np.float32)
        wpos = len(neg) / max(len(pos), 1)   # balance the classes
        sw = np.where(y == 1, wpos, 1.0).astype(np.float32)
        mu = x.mean(0)
        xc = x - mu
        w = np.zeros(x.shape[1], np.float32)
        b = 0.0
        lr = 0.5
        for _it in range(300):
            z = xc @ w + b
            p = 1 / (1 + np.exp(-np.clip(z, -30, 30)))
            g = (p - y) * sw
            w -= lr * ((xc.T @ g) / sw.sum() + 1e-3 * w)
            b -= lr * float(g.sum() / sw.sum())
        prob = 1 / (1 + np.exp(-np.clip((x_all - mu) @ w + b, -30, 30)))
        cs, cp = norm(pos.mean(0, keepdims=True))[0], norm(x_all[~flag].mean(0, keepdims=True))[0]
        closer = x_all @ cs > x_all @ cp
        new = (prob > threshold) & closer
        if (new == flag).all():
            break
        flag = new
        pos = np.concatenate([s, x_all[flag & (prob > 0.9)]])
    return flag


def _cached_embeddings(paths: list[Path], model: Path, cache: Path) -> np.ndarray:
    """Phone-model fingerprints, reusing earlier ones (keyed by file path, size and model file size), so the
    full pack, the light pack and later top-up runs fingerprint each photo only once."""
    from .freemodel import embed_tflite

    if not paths:
        return np.zeros((0, config.EMBED_DIM), np.float32)
    tag = f"{model.stat().st_size}"
    known: dict[str, np.ndarray] = {}
    if cache.exists():
        try:
            z = np.load(cache, allow_pickle=False)
            if str(z["tag"]) == tag:
                known = dict(zip(z["keys"].tolist(), z["vecs"]))
        except Exception:  # noqa: BLE001
            known = {}
    keys = [f"{p}|{p.stat().st_size}" for p in paths]
    todo = [i for i, k in enumerate(keys) if k not in known]
    print(f"FINGERPRINTS {len(paths)} photos, {len(paths) - len(todo)} cached, {len(todo)} new", flush=True)
    if todo:
        new = embed_tflite([paths[i] for i in todo], model)
        for i, v in zip(todo, new):
            known[keys[i]] = v
        ks = list(known)
        np.savez(cache, tag=np.array(tag), keys=np.array(ks), vecs=np.stack([known[k] for k in ks]).astype(np.float32))
    return np.stack([known[k] for k in keys]).astype(np.float32)


def starter_tier(content: dict) -> str:
    """How many photos the in-app starter pack keeps. Deadly keeps every photo. Toxic is next.

    Pack content uses toxicity serious (and edibility toxic) for a poisonous species. Those used to
    fall through to the smallest cap, so a toxic plant could lose the picture that identifies it.
    """
    tox = (content.get("toxicity") or "").lower()
    edi = (content.get("edibility") or "").lower()
    if tox == "deadly" or edi == "deadly":
        return "deadly"
    if tox in ("serious", "poisonous", "toxic") or edi == "toxic":
        return "poison"
    if edi.startswith("edible"):
        return "food"
    return ""


def light_pack(full: Path, out: Path, max_bytes: int = 90_000_000) -> dict:
    """The copy bundled inside the app, cut from the full pack so it fits in git (GitHub refuses files over
    100 MB). Every photo of every deadly plant, as many as fit for poisonous plants, then for plants with
    food use, and at least one photo for everything else. Same photo-ID fingerprints as the full pack.

    Its version is one below the full pack, so the app always offers the full photo set as an update."""
    import shutil
    import sqlite3

    src = sqlite3.connect(full)
    tier = {}
    for sid, js in src.execute("SELECT species_id, json FROM content"):
        try:
            d = json.loads(js)
        except json.JSONDecodeError:
            continue
        tier[sid] = starter_tier(d)
    by = _group(src.execute("SELECT species_id, id FROM photo ORDER BY species_id, rank, id"))
    src.close()
    out.parent.mkdir(parents=True, exist_ok=True)
    result = {}
    for poison, food, other in ((12, 6, 3), (12, 4, 2), (12, 3, 2), (12, 3, 1), (8, 3, 1), (6, 2, 1), (4, 2, 1), (3, 1, 1), (1, 1, 1)):
        cap = {"deadly": 99, "poison": poison, "food": food, "": other}
        keep = [i for sid, ids in by.items() for i in ids[:cap[tier.get(sid, "")]]]
        shutil.copyfile(full, out)
        con = sqlite3.connect(out)
        con.execute("CREATE TEMP TABLE keep(id INTEGER PRIMARY KEY)")
        con.executemany("INSERT INTO keep VALUES (?)", [(i,) for i in keep])
        con.execute("DELETE FROM photo WHERE id NOT IN (SELECT id FROM keep)")
        con.execute("DROP TABLE IF EXISTS refpage")   # only the cloud needs it
        v = int(dict(con.execute("SELECT key, value FROM meta"))["pack_version"]) - 1
        con.execute("UPDATE meta SET value = ? WHERE key = 'pack_version'", (str(v),))
        con.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('light', '1')")
        con.commit()
        con.execute("VACUUM")
        con.close()
        result = {"version": v, "caps": cap, "photos": len(keep), "bytes": out.stat().st_size}
        print("LIGHT", result, flush=True)
        if result["bytes"] < max_bytes:
            break
    return result


def _group(rows):
    g: dict = {}
    for k, v in rows:
        g.setdefault(k, []).append(v)
    return g
