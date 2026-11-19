"""Openly licensed photos for species the packs tag deadly or toxic.

A photo is kept only when the source names this species (or a synonym of the accepted name)
and the licence is one the pack may ship. No-derivatives licences, Plants For A Future, and
tropical.theferns.info are never used. Needs-ID iNaturalist observations are not used.
A photo of a different species, including a lookalike in the same genus, is rejected.

Genus and family warnings are the exception that is pinned by hand. A row with stage
"example" and a `depicts` name is a living photo of a representative species, captioned
"Example: <name>". It is shown on the warning and is never used as a fingerprint of the
named entry.
"""
from __future__ import annotations

import csv
import hashlib
import json
import re
import sqlite3
import urllib.parse
import urllib.request
from pathlib import Path

from . import config
from .http import USER_AGENT, get_json

LIVING = {"field", "flower", "fruit", "leaves", "closeup", "seedling", "bark"}
# Shown in the app, but not a photo of this species (or not a living plant).
# "example" is a labeled photo of a representative species on a genus or family warning.
NOT_LIVING = {"specimen", "related", "exact_specimen", "example"}
BINOMIAL_RE = re.compile(r"([A-Z][a-z]{2,})[ _]([a-z]{3,})")
RANK_STOP = {"subsp", "subspecies", "ssp", "var", "variety", "f", "forma", "cv", "cultivar"}
BLOCKED_HOSTS = ("pfaf.org", "theferns.info", "tropical.theferns.info")
FUNGI_OBS_GENERA = {"amanita", "galerina", "lepiota", "cortinarius", "inocybe", "pholiotina", "conocybe", "gyromitra"}

FIELDS = [
    "scientific_name", "accepted_name", "depicts", "image_url", "license", "observer", "page",
    "stage", "source", "commercial_ok", "file",
]


def binomial(name: str | None) -> str:
    """'Amanita borneensis Corner' / 'Cycas rumphii subsp. x' -> 'amanita borneensis'."""
    if not name:
        return ""
    parts: list[str] = []
    for raw in name.replace("×", " ").replace("?", " ").split():
        word = raw.strip(".,;()[]")
        if not word:
            continue
        low = word.lower()
        if low in {"x", "×", "hybrid"}:
            continue
        if low in RANK_STOP:
            break
        parts.append(word)
        if len(parts) == 2:
            break
    if len(parts) < 2:
        return ""
    return f"{parts[0]} {parts[1]}".lower()


def photo_names(resolved: dict) -> set[str]:
    """Names a photo may carry: the pack name, the accepted name, and genus transfers of that epithet.

    A long GBIF synonym list can include heterotypic names. Those are not used as a picture of this
    species. A photo has to be identified as this name or the accepted name.
    """
    query = binomial(resolved.get("query") or "")
    accepted = binomial(resolved.get("accepted") or resolved.get("query") or "")
    epithet = accepted.split()[-1] if accepted else ""
    names = {query, accepted}
    for alt in resolved.get("allowed") or []:
        if epithet and alt.split()[-1] == epithet:
            names.add(alt)
    return {n for n in names if n}


def name_ok(stated: str | None, allowed: set[str]) -> bool:
    """True when the stated name is this species or one of its synonyms. Genus-only is not enough."""
    return binomial(stated) in allowed and bool(binomial(stated))


def wrong_species_title(title: str, allowed: set[str], genus: str) -> bool:
    """True when a filename states a binomial that is not this species."""
    found = BINOMIAL_RE.findall(title or "")
    if not found:
        return False
    genus = genus.lower()
    for g, epithet in found:
        stated = f"{g} {epithet}".lower()
        if stated in allowed:
            continue
        if g.lower() == genus or g.lower() != genus:
            return True
    return False


def content_tag(content: dict | None) -> str:
    """'deadly', 'toxic', or '' from pack content. Family and genus warnings count: the app badges them."""
    if not content:
        return ""
    tox = (content.get("toxicity") or "").lower()
    edi = (content.get("edibility") or "").lower()
    if tox == "deadly" or edi == "deadly":
        return "deadly"
    if edi == "toxic" or tox in ("serious", "toxic", "poisonous"):
        return "toxic"
    return ""


def _allowed_license(raw: str | None) -> str | None:
    if not raw:
        return None
    lic = config.photo_license(raw)
    if lic:
        return lic
    from .gbif_photos import normalize_license_url

    lic = normalize_license_url(raw)
    return lic if lic in config.PHOTO_LICENSES else None


def _blocked(url: str | None) -> bool:
    low = (url or "").lower()
    return any(host in low for host in BLOCKED_HOSTS)


def resolve_gbif(name: str) -> dict:
    """Accepted species and the names a photo may use. Empty key when GBIF will not match a species."""
    allowed = {binomial(name)} - {""}
    out = {"query": name, "accepted": name, "key": None, "allowed": allowed, "kingdom": ""}
    try:
        match = get_json(f"{config.GBIF_API}/species/match", {"name": name, "strict": "true", "verbose": "true"})
    except Exception:
        return out
    if match.get("matchType") in (None, "NONE") or (match.get("rank") or "").upper() != "SPECIES":
        return out
    accepted_key = match.get("acceptedUsageKey") or match.get("usageKey")
    accepted = match.get("canonicalName") or match.get("species") or name
    # A synonym match still carries the accepted species in `species` when verbose.
    if match.get("species"):
        accepted = match["species"]
    try:
        detail = get_json(f"{config.GBIF_API}/species/{int(accepted_key)}")
        if (detail.get("rank") or "").upper() == "SPECIES":
            accepted = detail.get("canonicalName") or accepted
            out["kingdom"] = detail.get("kingdom") or ""
    except Exception:
        detail = {}
    names = {binomial(name), binomial(accepted), binomial(match.get("canonicalName"))}
    try:
        syn = get_json(f"{config.GBIF_API}/species/{int(accepted_key)}/synonyms", {"limit": 300})
        for row in syn.get("results") or []:
            if (row.get("rank") or "SPECIES").upper() in ("SPECIES", "SUBSPECIES", "VARIETY", "FORM"):
                names.add(binomial(row.get("canonicalName") or row.get("scientificName")))
    except Exception:
        pass
    out["accepted"] = accepted
    out["key"] = int(accepted_key)
    out["allowed"] = {n for n in names if n}
    return out


def _candidate(name: str, url: str, license_: str, observer: str, page: str, stage: str, source: str) -> dict:
    return {
        "scientific_name": name,
        "image_url": url,
        "license": license_,
        "observer": (observer or "")[:200],
        "page": page or url,
        "stage": stage if stage in LIVING or stage == "exact_specimen" else "field",
        "source": source,
    }


def from_wikidata(resolved_by_name: dict[str, dict]) -> dict[str, list[dict]]:
    """P18 images whose Wikidata taxon name is this species. One SPARQL query for the whole batch."""
    from .commercial_photos import from_p18, wikidata_lookup

    names = list(resolved_by_name)
    keys = {n: str(r["key"]) for n, r in resolved_by_name.items() if r.get("key")}
    found = wikidata_lookup(names, keys)
    out: dict[str, list[dict]] = {}
    for name, info in found.items():
        allowed = photo_names(resolved_by_name.get(name) or {"query": name, "accepted": name})
        genus = (resolved_by_name.get(name, {}).get("accepted") or name).split()[0]
        rows = []
        for rec in from_p18(info.get("images") or [], resolved_by_name.get(name, {}).get("accepted") or name):
            if _blocked(rec.get("url")) or _blocked(rec.get("page")):
                continue
            if wrong_species_title(rec.get("page") or rec.get("url") or "", allowed, genus):
                continue
            rows.append(_candidate(
                name, rec["url"], rec["license"], rec.get("observer") or "", rec.get("page") or "",
                rec.get("stage") or "field", "wikidata",
            ))
        if rows:
            out[name] = rows
    return out


def from_commons(resolved: dict) -> list[dict]:
    """Files in the Commons category of this species (and of its accepted name). Curated categories only."""
    from .commercial_photos import from_commons_category

    name = resolved["query"]
    allowed = photo_names(resolved)
    genus = (resolved.get("accepted") or name).split()[0]
    rows = []
    seen = set()
    for label in dict.fromkeys([name, resolved.get("accepted") or name]):
        if not label or " " not in label:
            continue
        try:
            got = from_commons_category(label, label)
        except Exception:
            got = []
        for rec in got:
            url = rec.get("url") or ""
            if not url or url in seen or _blocked(url) or _blocked(rec.get("page")):
                continue
            if wrong_species_title(rec.get("page") or url, allowed, genus):
                continue
            seen.add(url)
            rows.append(_candidate(
                name, url, rec["license"], rec.get("observer") or "", rec.get("page") or "",
                rec.get("stage") or "field", "commons",
            ))
    return rows


def from_gbif(resolved: dict, want: int = 8) -> list[dict]:
    """GBIF still images of this accepted species. The occurrence name must be the species or a synonym."""
    key = resolved.get("key")
    if not key:
        return []
    name = resolved["query"]
    allowed = photo_names(resolved)
    rows = []
    batches = []
    for basis in ("HUMAN_OBSERVATION", ""):
        params = {"taxonKey": str(key), "mediaType": "StillImage", "limit": "20"}
        if basis:
            params["basisOfRecord"] = basis
        try:
            batches.append(get_json(f"{config.GBIF_API}/occurrence/search", params))
        except Exception:
            continue
    seen_occ = set()
    occurrences = []
    for data in batches:
        for occ in data.get("results") or []:
            if occ.get("key") in seen_occ:
                continue
            seen_occ.add(occ.get("key"))
            occurrences.append(occ)
    for occ in occurrences:
        rank = (occ.get("taxonRank") or "").upper()
        if rank and rank not in ("SPECIES", "SUBSPECIES", "VARIETY", "FORM"):
            continue
        stated = occ.get("species") or occ.get("acceptedScientificName") or occ.get("scientificName") or ""
        if not name_ok(stated, allowed) and not name_ok(occ.get("scientificName"), allowed):
            continue
        # iNaturalist records on GBIF are research-grade for this dataset; other datasets are the publisher's.
        dataset = str(occ.get("datasetKey") or "")
        from .gbif_photos import INAT_RESEARCH_GRADE, smaller_url

        if "inaturalist" in " ".join([
            dataset, str(occ.get("publisher") or ""), str(occ.get("institutionCode") or ""),
            str(occ.get("references") or ""),
        ]).lower() and dataset != INAT_RESEARCH_GRADE:
            continue
        stage = "exact_specimen" if occ.get("basisOfRecord") == "PRESERVED_SPECIMEN" else "field"
        for media in (occ.get("media") or [])[:2]:
            url = media.get("identifier") or ""
            if not url or url.endswith("/manifest") or _blocked(url):
                continue
            lic = _allowed_license(media.get("license")) or _allowed_license(occ.get("license"))
            if not lic:
                continue
            if "inaturalist" in url.lower():
                url = smaller_url(url)
            refs = media.get("references") or ""
            if "inaturalist.org/observations/" in refs or "commons.wikimedia.org" in refs:
                page = refs
            else:
                page = f"https://www.gbif.org/occurrence/{occ.get('key')}"
            rows.append(_candidate(
                name, url, lic,
                media.get("rightsHolder") or media.get("creator") or occ.get("recordedBy") or "",
                page, stage, "gbif",
            ))
            if len(rows) >= want:
                return rows
    return rows


def from_inat(resolved: dict, want: int = 6) -> list[dict]:
    """Research-grade iNaturalist observations whose taxon is this species, not a genus-level guess."""
    if not config.ALLOW_INATURALIST:
        return []
    accepted = resolved.get("accepted") or resolved["query"]
    allowed = photo_names(resolved)
    try:
        taxa = get_json("https://api.inaturalist.org/v1/taxa", {
            "q": accepted, "rank": "species", "per_page": 4, "is_active": "true",
        })
    except Exception:
        return []
    def pick(payload) -> int | None:
        for taxon in payload.get("results") or []:
            if name_ok(taxon.get("name"), allowed):
                return taxon.get("id")
        return None

    taxon_id = pick(taxa)
    if not taxon_id:
        epithet = binomial(accepted).split()[-1] if binomial(accepted) else ""
        for alt in sorted(allowed):
            if not epithet or alt.split()[-1] != epithet or alt == binomial(accepted):
                continue
            pretty = alt.split()[0].capitalize() + " " + alt.split()[1]
            try:
                taxa = get_json("https://api.inaturalist.org/v1/taxa", {
                    "q": pretty, "rank": "species", "per_page": 4, "is_active": "true",
                })
            except Exception:
                continue
            taxon_id = pick(taxa)
            if taxon_id:
                break
    if not taxon_id:
        return []
    try:
        data = get_json("https://api.inaturalist.org/v1/observations", {
            "taxon_id": taxon_id, "photos": "true",
            "photo_license": "cc0,cc-by,cc-by-sa,cc-by-nc,cc-by-nc-sa",
            "quality_grade": "research", "per_page": str(max(want, 8)),
            "order_by": "votes", "order": "desc",
        })
    except Exception:
        return []
    rows = []
    for obs in data.get("results") or []:
        if (obs.get("quality_grade") or "") != "research":
            continue
        taxon = obs.get("taxon") or {}
        if taxon.get("rank") not in ("species", "subspecies", "variety", "form"):
            continue
        if not name_ok(taxon.get("name"), allowed):
            continue
        user = obs.get("user") or {}
        observer = user.get("name") or user.get("login") or ""
        page = obs.get("uri") or f"https://www.inaturalist.org/observations/{obs.get('id')}"
        for photo in (obs.get("photos") or [])[:2]:
            lic = _allowed_license(photo.get("license_code"))
            url = (photo.get("url") or "").replace("/square.", "/medium.")
            if not lic or not url or _blocked(url):
                continue
            rows.append(_candidate(resolved["query"], url, lic, observer, page, "field", "inaturalist"))
            if len(rows) >= want:
                return rows
    return rows


def from_mushroom_observer(resolved: dict, want: int = 4) -> list[dict]:
    """Mushroom Observer observations of this species. Used for fungi when the other sources are short."""
    genus = (resolved.get("accepted") or resolved["query"]).split()[0].lower()
    if genus not in FUNGI_OBS_GENERA:
        return []
    allowed = photo_names(resolved)
    query = urllib.parse.urlencode({
        "format": "json", "detail": "high", "has_images": "true",
        "names": resolved.get("accepted") or resolved["query"],
    })
    url = f"https://mushroomobserver.org/api2/observations?{query}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=40) as resp:
            data = json.loads(resp.read().decode())
    except Exception:
        return []
    results = data.get("results") or data.get("observations") or []
    if isinstance(data, list):
        results = data
    rows = []
    for obs in results:
        if not isinstance(obs, dict):
            continue
        consensus = obs.get("consensus") or obs.get("name") or {}
        if isinstance(consensus, dict):
            stated = consensus.get("name") or consensus.get("text_name") or ""
        else:
            stated = str(consensus)
        if not name_ok(stated, allowed):
            continue
        images = obs.get("images") or obs.get("primary_image") or []
        if isinstance(images, dict):
            images = [images]
        owner = obs.get("owner") or obs.get("user") or {}
        observer = owner.get("legal_name") or owner.get("login") or "" if isinstance(owner, dict) else str(owner)
        page = obs.get("url") or ""
        if obs.get("id") and not page:
            page = f"https://mushroomobserver.org/observations/{obs['id']}"
        for image in images[:2]:
            if not isinstance(image, dict):
                continue
            lic = _allowed_license(image.get("license") or image.get("copyright_holder") or obs.get("license"))
            # Mushroom Observer often puts the licence on the image as a short name.
            if lic is None:
                lic = _allowed_license((image.get("license_name") or obs.get("license_name") or ""))
            src = image.get("original_url") or image.get("medium_url") or image.get("url") or ""
            if not lic or not src or _blocked(src):
                continue
            rows.append(_candidate(resolved["query"], src, lic, observer, page, "field", "mushroom-observer"))
            if len(rows) >= want:
                return rows
    return rows


def _rank(row: dict) -> tuple:
    living = 0 if row["stage"] in LIVING else 1
    commercial = 0 if config.commercial_license(row["license"]) else 1
    source = {"wikidata": 0, "commons": 1, "inaturalist": 2, "mushroom-observer": 3, "gbif": 4}.get(row["source"], 5)
    return (living, commercial, source)


def select_photos(rows: list[dict], want: int = 2) -> list[dict]:
    """Up to `want` living photos, else one pressed specimen of this species. Drops duplicate URLs."""
    seen = set()
    ordered = []
    for row in sorted(rows, key=_rank):
        url = row.get("image_url") or ""
        if not url or url in seen:
            continue
        if _allowed_license(row.get("license")) is None:
            continue
        seen.add(url)
        ordered.append(row)
    living = [r for r in ordered if r["stage"] in LIVING]
    if living:
        return living[:want]
    sheets = [r for r in ordered if r["stage"] == "exact_specimen"]
    return sheets[:1]


def download_image(url: str, dest: Path) -> bool:
    """Download and re-encode as JPEG. False when the URL is not a photograph."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as resp:
            blob = resp.read(20_000_000)
            ctype = (resp.headers.get("Content-Type") or "").lower()
    except Exception:
        return False
    if blob[:5] == b"<?xml" or blob[:1] == b"<" or "text/html" in ctype or "application/json" in ctype:
        return False
    try:
        import io

        from PIL import Image

        Image.MAX_IMAGE_PIXELS = 200_000_000
        im = Image.open(io.BytesIO(blob))
        im.seek(0)
        if min(im.width, im.height) < 180:
            return False
        im = im.convert("RGB")
        im.thumbnail((1280, 1280))
        dest.parent.mkdir(parents=True, exist_ok=True)
        im.save(dest, "JPEG", quality=85)
    except Exception:
        return False
    from .commercial_photos import _looks_like_micrograph, looks_like_sheet

    if looks_like_sheet(dest, url) or _looks_like_micrograph(dest) or _blank_scan(dest):
        dest.unlink(missing_ok=True)
        return False
    return True


def _blank_scan(path: Path) -> bool:
    """A herbarium scan that is almost all bare paper, with no plant to recognise."""
    try:
        from PIL import Image

        im = Image.open(path).convert("HSV").resize((48, 48))
        pix = list(im.getdata())
        sat = sum(p[1] for p in pix) / len(pix)
        val = sum(p[2] for p in pix) / len(pix)
        return sat < 30 and val > 170
    except Exception:
        return False


def search_one(name: str, resolved: dict | None = None, want: int = 2) -> list[dict]:
    """Living photos for one species, from curated sources first, then research-grade observations."""
    resolved = resolved or resolve_gbif(name)
    rows: list[dict] = []
    wiki = from_wikidata({name: resolved}).get(name) or []
    rows.extend(wiki)
    if sum(1 for r in rows if r["stage"] in LIVING) < want:
        rows.extend(from_commons(resolved))
    if sum(1 for r in rows if r["stage"] in LIVING) < want:
        rows.extend(from_gbif(resolved))
    if sum(1 for r in rows if r["stage"] in LIVING) < want:
        rows.extend(from_inat(resolved))
    if sum(1 for r in rows if r["stage"] in LIVING) < want:
        rows.extend(from_mushroom_observer(resolved))
    if sum(1 for r in rows if r["stage"] in LIVING) < want:
        rows.extend(_from_wiki_pages(resolved))
    return select_photos(rows, want=want)


def _from_wiki_pages(resolved: dict) -> list[dict]:
    """Wikipedia lead images and Commons files whose title is this species. Still name-checked."""
    from .commercial_photos import from_commons_search, from_wikipedias

    name = resolved["query"]
    allowed = photo_names(resolved)
    genus = (resolved.get("accepted") or name).split()[0]
    rows = []
    accepted = resolved.get("accepted") or name
    labels = list(dict.fromkeys([name, accepted]))
    # Genus transfers share the epithet (Daphne composita / Eriosolena composita). A different epithet
    # can be a heterotypic synonym and is not used as a picture of this species.
    epithet = binomial(accepted).split()[-1] if binomial(accepted) else ""
    for alt in sorted(allowed):
        if not epithet or alt.split()[-1] != epithet:
            continue
        pretty = alt.split()[0].capitalize() + " " + alt.split()[1]
        if pretty not in labels and pretty.lower() not in {x.lower() for x in labels}:
            labels.append(pretty)
    for label in labels[:6]:
        try:
            got = from_wikipedias(label) + from_commons_search(label, limit=8)
        except Exception:
            got = []
        for rec in got:
            url = rec.get("url") or ""
            if not url or _blocked(url) or wrong_species_title(rec.get("page") or url, allowed, genus):
                continue
            rows.append(_candidate(
                name, url, rec["license"], rec.get("observer") or "", rec.get("page") or "",
                rec.get("stage") or "field", "commons",
            ))
    return rows


def gaps_in_pack(pack: Path, tag: str) -> list[dict]:
    """Species tagged `tag` that have no living photo of their own."""
    con = sqlite3.connect(pack)
    living: dict[int, int] = {}
    for sid, stage in con.execute("SELECT species_id, stage FROM photo"):
        if stage not in NOT_LIVING:
            living[sid] = living.get(sid, 0) + 1
    out = []
    for sid, name, kingdom, genus, family, raw in con.execute(
        "SELECT s.id, s.scientific_name, s.kingdom, s.genus, s.family, c.json "
        "FROM species s JOIN content c ON c.species_id = s.id"
    ):
        try:
            content = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if content_tag(content) != tag:
            continue
        if living.get(sid, 0) > 0:
            continue
        harms = ((content.get("if_eaten") or {}).get("harms") or {})
        out.append({
            "scientific_name": name,
            "kingdom": kingdom,
            "genus": genus or name.split()[0],
            "family": family or "",
            "level": content.get("level") or "",
            "group_taxon": content.get("group_taxon") or "",
            "harms_ok": bool(harms.get("symptoms") and harms.get("onset") and harms.get("what_to_do")),
        })
    con.close()
    out.sort(key=lambda r: ({"species": 0, "quick": 1, "genus": 2, "family": 3}.get(r["level"], 9), r["scientific_name"]))
    return out


def write_rows(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k) or "" for k in FIELDS})


def fetch_gaps(pack: Path, dest_dir: Path, *, tags: tuple[str, ...] = ("deadly", "toxic"),
               per_species: int = 2, limit: int | None = None) -> list[dict]:
    """Download photos for species in `pack` that are tagged and have no living photo."""
    from .commercial_photos import looks_like_sheet

    wanted = []
    for tag in tags:
        wanted.extend(gaps_in_pack(pack, tag))
    if limit is not None:
        wanted = wanted[:limit]
    saved: list[dict] = []
    manifest = dest_dir / "danger_photos.csv"
    for index, spec in enumerate(wanted, start=1):
        name = spec["scientific_name"]
        print(f"[{index}/{len(wanted)}] {spec['level']} {name}", flush=True)
        try:
            resolved = resolve_gbif(name)
            chosen = search_one(name, resolved, want=per_species)
        except Exception as exc:  # noqa: BLE001
            print(f"  search failed: {exc!r}", flush=True)
            continue
        kept = 0
        for nth, row in enumerate(chosen):
            digest = hashlib.sha1(row["image_url"].encode()).hexdigest()[:16]
            dest = dest_dir / "img" / f"{digest}.jpg"
            if not dest.exists() and not download_image(row["image_url"], dest):
                print(f"  skip download {row['source']} {row['image_url'][:80]}", flush=True)
                continue
            if looks_like_sheet(dest, row["image_url"], row["page"]):
                # A white sheet is not a field photo. Keep searching; do not ship it as the cover.
                dest.unlink(missing_ok=True)
                print(f"  sheet {row['source']}", flush=True)
                continue
            row = {
                **row,
                "accepted_name": resolved.get("accepted") or name,
                "commercial_ok": "1" if config.commercial_license(row["license"]) else "0",
                "file": str(dest),
            }
            saved.append(row)
            kept += 1
        print(f"  kept {kept}", flush=True)
        write_rows(manifest, saved)
    return saved


def display_observer(row: dict) -> str:
    """Photographer credit. An example photo also names the species in the picture."""
    observer = (row.get("observer") or "").strip()
    depicts = (row.get("depicts") or "").strip()
    if (row.get("stage") or "") == "example" and depicts and "example:" not in observer.lower():
        observer = f"{observer} — example: {depicts}".strip()
        if observer.startswith("—"):
            observer = observer.lstrip("— ").strip()
    return observer


def load_pinned(region_id: str) -> list[dict]:
    """Photos recorded for a region (data/regions/<id>/danger_photos.csv) whose files are still on disk.

    Rows name the species, the photographer, the licence and the source page. A later pack build
    includes them when the file is present. Nothing is fetched from pfaf.org or tropical.theferns.info.
    """
    path = config.DATA_REGIONS / region_id / "danger_photos.csv"
    if not path.exists():
        return []
    cache = config.WORK / "photos_commercial" / region_id / "danger"
    rows = []
    for row in csv.DictReader(path.open(encoding="utf-8")):
        if _blocked(row.get("image_url")) or _blocked(row.get("page")):
            continue
        if _allowed_license(row.get("license")) is None:
            continue
        file_ = Path(row["file"]) if (row.get("file") or "").strip() else None
        if file_ is None or not file_.is_file():
            digest = hashlib.sha1((row.get("image_url") or "").encode()).hexdigest()[:16]
            file_ = cache / f"{digest}.jpg"
            if not file_.is_file() and row.get("image_url"):
                download_image(row["image_url"], file_)
        if not file_.is_file():
            continue
        rows.append({
            "scientific_name": row["scientific_name"],
            "path": str(file_),
            "license": row["license"],
            "observer": display_observer(row),
            "page": row.get("page") or row.get("image_url") or "",
            "url": row.get("page") or row.get("image_url") or "",
            # Two photos of one observation share a page. The image URL is what makes each one distinct.
            "image_url": row.get("image_url") or "",
            "stage": row.get("stage") or "field",
            "source": row.get("source") or "danger",
            "depicts": (row.get("depicts") or "").strip(),
        })
    return rows
