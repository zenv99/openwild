"""Community photos: pictures OpenWild users took themselves and released under CC BY 4.0.

Flow
  1. In the app a user picks a plant, takes close-ups by growth stage, types the name they want credited,
     and ticks "I took these and release them under CC BY 4.0". The app strips location data, saves the
     photos to the phone and opens a pre-filled GitHub issue (label ``photo-contribution``). The user
     attaches the photos there. Nothing is uploaded without those taps.
  2. A maintainer checks the photos (right plant, the person's own work, nothing private) and adds the
     label ``approved``.
  3. ``ingest_issues`` downloads approved photos, re-encodes them (which drops all metadata), and records
     them in ``content/community/<region>/community.csv``. Pack builds put community photos first, credited
     "Name (OpenWild community)".

Over time this replaces stock photos that carry restrictions (non-commercial licences) with photos the
community owns and shares freely.
"""

from __future__ import annotations

import csv
import io
import re
from pathlib import Path

from . import config

COMMUNITY = config.REPO_ROOT / "content" / "community"
FIELDS = ["scientific_name", "stage", "observer", "license", "page", "url", "path", "source", "issue"]
STAGES = {"seedling", "leaves", "flower", "fruit", "closeup", "bark", "field"}
MAX_SIDE = 2048

_FIELD = re.compile(r"^\s*\**\s*(species|region|credit|licen[cs]e)\s*\**\s*:\s*\**\s*(.+?)\s*$", re.I | re.M)
_IMG_MD = re.compile(r"!\[([^\]]*)\]\((https://[^)\s]+)\)")
_IMG_TAG = re.compile(r"<img\b[^>]*>", re.I)
_ATTR = re.compile(r'(alt|src)\s*=\s*"([^"]*)"', re.I)
_ALLOWED_HOSTS = ("https://github.com/user-attachments/", "https://user-images.githubusercontent.com/",
                  "https://private-user-images.githubusercontent.com/")


def parse_issue(body: str) -> dict:
    """Read the fields the app pre-fills, plus every attached image with its stage (from the file name)."""
    body = _from_issue_form(body or "")
    fields = {k.lower().replace("licence", "license"): v.strip() for k, v in _FIELD.findall(body)}
    images = []
    tags = []
    for tag in _IMG_TAG.findall(body or ""):
        a = {k.lower(): v for k, v in _ATTR.findall(tag)}
        tags.append((a.get("alt", ""), a.get("src", "")))
    for alt, url in _IMG_MD.findall(body or "") + tags:
        if not url.startswith(_ALLOWED_HOSTS) or url in {u for _, u in images}:
            continue
        images.append((stage_from_name(alt), url))
    return {
        "species": fields.get("species", "").strip("*_ "),
        "region": fields.get("region", "").strip("*_ ").upper(),
        "credit": clean_credit(fields.get("credit", "")),
        "license": fields.get("license", ""),
        "images": images,
    }


def _from_issue_form(body: str) -> str:
    """GitHub issue forms render each field as '### Label' then the value. Turn those into 'Label: value' lines.
    A ticked licence box ('- [X] ... CC BY 4.0 ...') becomes 'Licence: CC BY 4.0'."""
    out = []
    for block in re.split(r"(?m)^###\s+", body):
        head, _, rest = block.partition("\n")
        key = head.strip().lower()
        val = rest.strip()
        if key in ("species", "region", "credit", "stages"):
            out.append(f"{head.strip()}: {val.splitlines()[0] if val else ''}")
            out.append(rest)
        elif key.startswith("licen"):
            if re.search(r"-\s*\[[xX]\].*CC BY 4\.0", val):
                out.append("Licence: CC BY 4.0")
        else:
            out.append(("### " + block) if out else block)
    return "\n".join(out)


def stage_from_name(name: str) -> str:
    """The app names files ``Genus_species__stage__n.jpg``; anything else counts as a general field photo."""
    parts = (name or "").lower().split("__")
    return parts[1] if len(parts) >= 2 and parts[1] in STAGES else "field"


def clean_credit(s: str) -> str:
    s = re.sub(r"[<>\[\]()`*_#|]", "", s or "").strip()
    return s[:60]


def licence_ok(s: str) -> bool:
    """Only CC BY 4.0 (or CC0) contributions are accepted, so community photos carry no restrictions."""
    t = (s or "").lower().replace(" ", "").replace("-", "")
    return t.startswith("ccby4") or t.startswith("cc0")


def reencode(data: bytes) -> bytes | None:
    """Decode and save again as a plain JPEG: no EXIF, no GPS, no camera serial. None if not an image."""
    from PIL import Image, ImageOps

    try:
        im = Image.open(io.BytesIO(data))
        im = ImageOps.exif_transpose(im).convert("RGB")
    except Exception:  # noqa: BLE001
        return None
    im.thumbnail((MAX_SIDE, MAX_SIDE))
    out = io.BytesIO()
    im.save(out, "JPEG", quality=88, optimize=True)
    return out.getvalue()


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def load(region_id: str) -> list[dict]:
    f = COMMUNITY / region_id / "community.csv"
    if not f.exists():
        return []
    rows = []
    for r in csv.DictReader(open(f, encoding="utf-8")):
        p = config.REPO_ROOT / r["path"]
        if p.exists():
            rows.append({**r, "path": str(p)})
    return rows


def ingest_issues(issues: list[dict], fetch, known_species: dict[str, set[str]]) -> dict:
    """Add approved contributions. ``fetch(url) -> bytes``; ``known_species`` maps region -> lowercase names.
    Returns {"added": [...], "rejected": {issue_number: reason}} so the workflow can reply on each issue."""
    added, rejected = [], {}
    for issue in issues:
        n = issue["number"]
        info = parse_issue(issue.get("body") or "")
        region = info["region"]
        name = info["species"]
        if region not in known_species or name.lower() not in known_species[region]:
            rejected[n] = f"species '{name}' is not in region '{region}'"
            continue
        if not licence_ok(info["license"]):
            rejected[n] = "photos must be released under CC BY 4.0"
            continue
        if not info["credit"]:
            rejected[n] = "no name to credit"
            continue
        if not info["images"]:
            rejected[n] = "no photos attached"
            continue
        folder = COMMUNITY / region / slug(name)
        folder.mkdir(parents=True, exist_ok=True)
        csv_path = COMMUNITY / region / "community.csv"
        existing = {r["url"] for r in csv.DictReader(open(csv_path, encoding="utf-8"))} if csv_path.exists() else set()
        new_rows = []
        for k, (stage, url) in enumerate(info["images"]):
            if url in existing:
                continue
            jpg = reencode(fetch(url))
            if jpg is None:
                continue
            dest = folder / f"{n}_{k}_{stage}.jpg"
            dest.write_bytes(jpg)
            new_rows.append({
                "scientific_name": name, "stage": stage, "observer": f"{info['credit']} (OpenWild community)",
                "license": "cc-by", "page": issue.get("html_url", ""), "url": url,
                "path": str(dest.relative_to(config.REPO_ROOT)), "source": "openwild-community", "issue": n,
            })
        if not new_rows:
            rejected[n] = "no readable photos"
            continue
        write_header = not csv_path.exists()
        with open(csv_path, "a", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=FIELDS)
            if write_header:
                w.writeheader()
            w.writerows(new_rows)
        added += new_rows
    return {"added": added, "rejected": rejected}


def credits(region_id: str) -> list[tuple[str, int]]:
    """Photographers by number of photos, for the thank-you list."""
    from collections import Counter

    c = Counter(r["observer"].replace(" (OpenWild community)", "") for r in load(region_id))
    return c.most_common()


def wanted(region_id: str, photos_csv: Path, top: int = 200) -> list[dict]:
    """Most-observed species with the fewest photos, for the 'photos wanted' list."""
    d = config.DATA_REGIONS / region_id
    have: dict[str, int] = {}
    if photos_csv.exists():
        for r in csv.DictReader(open(photos_csv, encoding="utf-8")):
            have[r["scientific_name"].lower()] = have.get(r["scientific_name"].lower(), 0) + 1
    for r in load(region_id):
        have[r["scientific_name"].lower()] = have.get(r["scientific_name"].lower(), 0) + 1
    rows = list(csv.DictReader(open(d / "species.csv", encoding="utf-8")))
    out = [{"scientific_name": r["scientific_name"], "common_name": r.get("common_name") or "",
            "photos": have.get(r["scientific_name"].lower(), 0), "obs": int(float(r.get("gbif_count") or 0))} for r in rows]
    out.sort(key=lambda r: (r["photos"] >= 3, r["photos"], -r["obs"]))
    return out[:top]


