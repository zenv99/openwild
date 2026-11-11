"""iNaturalist Open Dataset: download metadata, index plants & fungi, select photos per region.

The dataset is a public S3 bucket (no account needed). Metadata is ~4.5 GB compressed, four
tab-separated files: observations, observers, photos, taxa. We load it with DuckDB, which handles
tens of millions of rows on a laptop without loading everything into memory.
"""
from __future__ import annotations

import tarfile
from pathlib import Path

from . import config
from .http import download

DB_PATH = config.WORK / "inat.duckdb"
META_DIR = config.WORK / "inat_metadata"


def download_metadata() -> Path:
    tgz = config.WORK / "inaturalist-open-data-latest.tar.gz"
    if not tgz.exists():
        download(config.INAT_METADATA_URL, tgz)
    META_DIR.mkdir(parents=True, exist_ok=True)
    with tarfile.open(tgz) as tf:
        tf.extractall(META_DIR, filter="data")
    return META_DIR


def _find(name: str) -> str:
    hits = sorted(META_DIR.rglob(f"{name}*.csv*"))
    if not hits:
        raise FileNotFoundError(f"{name}.csv not found under {META_DIR}; run inat-download first")
    return str(hits[0])


def build_index() -> Path:
    """Create inat.duckdb with research-grade plant/fungus species observations and licensed photos."""
    import duckdb

    con = duckdb.connect(str(DB_PATH))
    read = "read_csv('{}', delim='\\t', header=true, quote='', all_varchar=false, ignore_errors=true)"
    con.execute(f"CREATE OR REPLACE TABLE taxa_raw AS SELECT * FROM {read.format(_find('taxa'))}")
    con.execute(
        f"""
        CREATE OR REPLACE TABLE taxa AS
        SELECT taxon_id, name AS scientific_name,
               CASE WHEN regexp_matches(ancestry, '(^|/){config.INAT_FUNGI}(/|$)') THEN 'Fungi' ELSE 'Plantae' END AS kingdom
        FROM taxa_raw
        WHERE rank = 'species' AND active
          AND (regexp_matches(ancestry, '(^|/){config.INAT_PLANTAE}(/|$)')
            OR regexp_matches(ancestry, '(^|/){config.INAT_FUNGI}(/|$)'))
        """
    )
    con.execute(
        f"""
        CREATE OR REPLACE TABLE obs AS
        SELECT o.observation_uuid, o.observer_id, o.latitude, o.longitude, o.taxon_id,
               month(TRY_CAST(o.observed_on AS DATE)) AS month
        FROM {read.format(_find('observations'))} o
        JOIN taxa t USING (taxon_id)
        WHERE o.quality_grade = 'research' AND o.latitude IS NOT NULL
        """
    )
    allowed = ", ".join(f"'{l}'" for l in sorted(config.ALLOWED_LICENSES))
    con.execute(
        f"""
        CREATE OR REPLACE TABLE photos AS
        SELECT p.photo_id, p.extension, lower(replace(p.license, '_', '-')) AS license,
               p.observer_id, p.position, o.observation_uuid, o.taxon_id, o.latitude, o.longitude, o.month
        FROM {read.format(_find('photos'))} p
        JOIN obs o USING (observation_uuid)
        WHERE lower(replace(p.license, '_', '-')) IN ({allowed})
        """
    )
    con.execute(f"CREATE OR REPLACE TABLE observers AS SELECT * FROM {read.format(_find('observers'))}")
    con.close()
    return DB_PATH


def region_stats_and_photos(region, species_csv: Path, out_dir: Path, per_species: int) -> dict[str, Path]:
    """For one region: per-species observation counts + month histogram, and a photo selection.

    Photos are taken from anywhere in the world (appearance rarely depends on country), preferring
    in-region observations, at most one photo per observation for variety, first-position photos first.
    """
    import duckdb

    out_dir.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect(str(DB_PATH), read_only=True)
    con.execute(f"CREATE TEMP TABLE region_species AS SELECT * FROM read_csv('{species_csv}', header=true)")
    con.execute(
        """
        CREATE TEMP TABLE sp AS
        SELECT r.*, t.taxon_id FROM region_species r
        JOIN taxa t ON lower(t.scientific_name) = lower(r.scientific_name)
        """
    )
    boxes = _boxes_sql(region.bbox, "o.")
    stats_path = out_dir / "species_stats.parquet"
    con.execute(
        f"""
        COPY (
          SELECT sp.taxon_id, sp.gbif_key, sp.scientific_name, sp.kingdom, sp.family, sp.genus, sp.common_name,
                 count(o.observation_uuid) AS obs_count,
                 list(o.month) FILTER (WHERE o.month IS NOT NULL) AS months
          FROM sp LEFT JOIN obs o ON o.taxon_id = sp.taxon_id AND ({boxes})
          GROUP BY ALL
        ) TO '{stats_path}' (FORMAT parquet)
        """
    )
    photos_path = out_dir / "photos.parquet"
    in_region = _boxes_sql(region.bbox, "")
    con.execute(
        f"""
        COPY (
          WITH ranked AS (
            SELECT p.*, ob.login AS observer,
                   row_number() OVER (PARTITION BY p.observation_uuid ORDER BY p.position) AS rn_obs
            FROM photos p JOIN sp USING (taxon_id) LEFT JOIN observers ob USING (observer_id)
          ),
          picked AS (
            SELECT *, row_number() OVER (
                PARTITION BY taxon_id ORDER BY (CASE WHEN {in_region} THEN 0 ELSE 1 END), hash(photo_id)
            ) AS rn
            FROM ranked WHERE rn_obs = 1
          )
          SELECT photo_id, extension, license, observer, observation_uuid, taxon_id
          FROM picked WHERE rn <= {per_species}
        ) TO '{photos_path}' (FORMAT parquet)
        """
    )
    con.close()
    return {"stats": stats_path, "photos": photos_path}


def _boxes_sql(bbox: list[list[float]], prefix: str) -> str:
    parts = [
        f"({prefix}longitude BETWEEN {b[0]} AND {b[2]} AND {prefix}latitude BETWEEN {b[1]} AND {b[3]})"
        for b in bbox
    ]
    return "(" + " OR ".join(parts) + ")" if parts else "TRUE"


def photo_url(photo_id: int, extension: str, size: str = "medium") -> str:
    return f"{config.INAT_BUCKET_URL}/photos/{photo_id}/{size}.{extension}"


def observation_url(observation_uuid: str) -> str:
    return f"https://www.inaturalist.org/observations/{observation_uuid}"
