"""Shared constants and paths."""
from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REGIONS_YAML = REPO_ROOT / "regions" / "regions.yaml"
CURATED_DIR = REPO_ROOT / "content" / "curated"
ANDROID_ASSETS = REPO_ROOT / "android" / "app" / "src" / "main" / "assets"
# Small, committed per-region data (species lists, names, descriptions, coverage summary).
DATA_REGIONS = REPO_ROOT / "data" / "regions"

# All large working files live here (override with OPENWILD_WORK=/path).
WORK = Path(os.environ.get("OPENWILD_WORK", REPO_ROOT / "work"))
CACHE = WORK / "cache"
PHOTOS_DIR = WORK / "photos"
EMB_DIR = WORK / "embeddings"
MODELS_DIR = WORK / "models"
PACKS_DIR = WORK / "packs"

# iNaturalist Open Dataset (public S3 bucket, no account needed)
INAT_BUCKET_URL = "https://inaturalist-open-data.s3.amazonaws.com"
INAT_METADATA_URL = f"{INAT_BUCKET_URL}/metadata/inaturalist-open-data-latest.tar.gz"
INAT_PLANTAE = 47126
INAT_FUNGI = 47170

# GBIF
GBIF_API = "https://api.gbif.org/v1"
GBIF_KINGDOM_KEYS = {"Plantae": 6, "Fungi": 5}

# Licenses usable in a free, non-commercial app. ND (no-derivatives) excluded.
ALLOWED_LICENSES = {"cc0", "cc-by", "cc-by-sa", "cc-by-nc", "cc-by-nc-sa"}
# Photos and fingerprints that ship in a region pack. Commercial-ok only:
# CC BY-NC is not used, and neither are ND licences. CC BY-SA is commercial-ok
# (share-alike) and is how most Wikimedia Commons files are published.
COMMERCIAL_LICENSES = {"cc0", "cc-by", "cc-by-sa"}

# Model contract (must match the app's assets/embedder.json, written by export).
# "dinov2s": free, no training — DINOv2 ViT-S/14 (Apache-2.0), runs on GitHub's CPUs. Default.
# "distilled": BioCLIP 2 distilled into MobileNetV4 (needs a free Kaggle/Colab GPU; more accurate later).
MODELS = {
    "dinov2s": {"id": "ow-dinov2s-384-v1", "dim": 384},
    "distilled": {"id": "ow-mnv4m-768-v1", "dim": 768},
}
MODEL = os.environ.get("OPENWILD_MODEL", "dinov2s")
MODEL_ID = MODELS[MODEL]["id"]
EMBED_DIM = MODELS[MODEL]["dim"]
INPUT_SIZE = 224

# Default confidence parameters (overwritten by calibration)
DEFAULT_PARAMS = {
    "temperature": 0.03,
    "photo_alpha": 0.6,
    "prior_beta": 0.5,
    "open_set_threshold": 0.30,
    "high_threshold": 0.85,
    "medium_threshold": 0.50,
    "text_proto_penalty": 0.05,
}

# Photo selection
PHOTOS_PER_SPECIES = 40          # reference photos used to build prototypes
TRAIN_PHOTOS_PER_SPECIES = 60    # photos used for distillation (global, all regions)
MAX_PROTOTYPES = 6


def normalize_license(raw: str | None) -> str | None:
    """'CC-BY-NC' / 'cc_by_nc' / 'CC BY-NC 4.0' / 'CC BY-SA 2.5 nl' -> 'cc-by-nc' / 'cc-by-sa'.
    Public-domain marks ('Public domain', 'PD-old', 'PD-self', 'PD-USGov', CC0) -> 'cc0'.
    Returns None if unknown/empty."""
    import re as _re

    if not raw:
        return None
    s = raw.strip().lower().replace("_", "-").replace(" ", "-")
    if s in ("cc0", "cc-0", "cc-zero", "public-domain", "pd") or s.startswith("pd-") or s.startswith("cc0-") \
            or s.startswith("public-domain-"):
        return "cc0"
    # Drop the version (1.0 .. 4.0) and any jurisdiction port suffix (de, nl, igo, ...).
    m = _re.fullmatch(r"(cc-by(?:-nc)?(?:-sa)?(?:-nd)?)(?:-\d(?:\.\d)?)?(?:-[a-z]{2,4})?", s)
    if m:
        return m.group(1)
    return s if s.startswith("cc-") and "-" not in s[3:] else None


def license_allowed(raw: str | None) -> bool:
    return normalize_license(raw) in ALLOWED_LICENSES


def commercial_license(raw: str | None) -> str | None:
    """Return a normalised commercial-ok licence, or None.

    Accepts short names ('CC BY-SA 4.0', 'cc-by-nc') and Creative Commons URLs.
    CC BY-NC and ND are rejected. iNaturalist terms are a separate check.
    """
    if not raw or not str(raw).strip():
        return None
    text = str(raw).strip()
    lower = text.lower()
    if "noncommercial" in lower or "-nc" in lower.replace("_", "-") or " by-nc" in lower:
        # Still parse so 'by-nc' is not mistaken for 'by' via a sloppy substring.
        lic = normalize_license(text)
        if lic in COMMERCIAL_LICENSES:
            return lic
        return None
    lic = normalize_license(text)
    if lic in COMMERCIAL_LICENSES:
        return lic
    u = lower
    if "publicdomain/zero" in u or "/publicdomain/" in u or "creativecommons.org/publicdomain/zero" in u:
        return "cc0"
    import re
    m = re.search(r"licenses/(by(?:-nc)?(?:-sa)?(?:-nd)?)", u)
    if m:
        name = "cc-" + m.group(1)
        return name if name in COMMERCIAL_LICENSES else None
    if "cc0" in u or "public-domain" in u or "public domain" in u:
        return "cc0"
    return None
