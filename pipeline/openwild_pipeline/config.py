"""Shared constants and paths."""
from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REGIONS_YAML = REPO_ROOT / "regions" / "regions.yaml"
CURATED_DIR = REPO_ROOT / "content" / "curated"
ANDROID_ASSETS = REPO_ROOT / "android" / "app" / "src" / "main" / "assets"

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

# Model contract (must match android/.../ml/Embedder.kt)
MODEL_ID = "ow-mnv4m-768-v1"
EMBED_DIM = 768
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
    """'CC-BY-NC' / 'cc_by_nc' / 'CC BY-NC 4.0' -> 'cc-by-nc'. Returns None if unknown/empty."""
    if not raw:
        return None
    s = raw.strip().lower().replace("_", "-").replace(" ", "-")
    for suffix in ("-4.0", "-3.0", "-2.0", "-1.0"):
        if s.endswith(suffix):
            s = s[: -len(suffix)]
    if s in ("cc0", "cc-0", "cc-zero", "public-domain", "pd"):
        return "cc0"
    return s if s.startswith("cc-") else None


def license_allowed(raw: str | None) -> bool:
    return normalize_license(raw) in ALLOWED_LICENSES
