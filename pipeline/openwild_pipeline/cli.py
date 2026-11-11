"""OpenWild pipeline command line.

Typical first run (see pipeline/README.md):
  python -m openwild_pipeline.cli content-validate
  python -m openwild_pipeline.cli inat-download && python -m openwild_pipeline.cli inat-index
  python -m openwild_pipeline.cli species PH && python -m openwild_pipeline.cli select PH && python -m openwild_pipeline.cli fetch PH
  python -m openwild_pipeline.cli teacher-embed PH ID TH ...      # GPU
  python -m openwild_pipeline.cli distill && python -m openwild_pipeline.cli export
  python -m openwild_pipeline.cli region PH --version 1           # embed + calibrate + build
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import config


def region_dir(region_id: str) -> Path:
    return config.WORK / "regions" / region_id


def student_ckpt() -> Path:
    return config.MODELS_DIR / "student.pt"


def cmd_content_validate(_):
    from .content import load_curated

    entries = load_curated(strict=True)
    print(f"OK: {len(entries)} curated entries valid")


def cmd_regions_export(_):
    from .regions import export_app_index

    print(export_app_index())


def cmd_regions_from_naturalearth(a):
    from .regions import regions_from_naturalearth

    sys.stdout.write(regions_from_naturalearth(Path(a.geojson)))


def cmd_inat_download(_):
    from .inat import download_metadata

    print(download_metadata())


def cmd_inat_index(_):
    from .inat import build_index

    print(build_index())


def cmd_species(a):
    from .gbif import build_species_list
    from .regions import get_region

    print(build_species_list(get_region(a.region), region_dir(a.region) / "species.csv"))


def cmd_select(a):
    from .inat import region_stats_and_photos
    from .regions import get_region

    d = region_dir(a.region)
    print(region_stats_and_photos(get_region(a.region), d / "species.csv", d, a.per_species))


def cmd_fetch(a):
    from .photos import fetch

    ok, failed = fetch(region_dir(a.region) / "photos.parquet", workers=a.workers)
    print(f"{a.region}: {ok} photos ok, {failed} failed")


def cmd_teacher_embed(a):
    from .distill import teacher_embed

    pqs = [region_dir(r) / "photos.parquet" for r in a.regions]
    print(teacher_embed(pqs, config.EMB_DIR / "teacher.npz", device=a.device))


def cmd_distill(a):
    from .distill import train_student

    print(train_student(config.EMB_DIR / "teacher.npz", student_ckpt(), epochs=a.epochs, device=a.device))


def cmd_export(a):
    from .export import export_tflite, parity_check

    out = export_tflite(student_ckpt(), int8=not a.float32)
    print(out, "parity cosine:", round(parity_check(student_ckpt(), out), 4))


def cmd_embed(a):
    from .embed import embed_region_photos

    d = region_dir(a.region)
    print(embed_region_photos(student_ckpt(), d / "photos.parquet", d / "student.npz", device=a.device))


def cmd_calibrate(a):
    import numpy as np

    from .calibrate import calibrate

    d = region_dir(a.region)
    data = np.load(d / "student.npz")
    params = calibrate(data["embs"], data["taxon_ids"])
    (d / "params.json").write_text(json.dumps(params, indent=2))
    print(params)


def cmd_build(a):
    import duckdb
    import numpy as np

    from .content import load_curated, wikipedia_summary
    from .inat import observation_url
    from .pack import SpeciesRecord, build_pack
    from .regions import get_region

    region = get_region(a.region)
    d = region_dir(a.region)
    curated = load_curated(strict=True)
    rel = duckdb.sql(f"SELECT * FROM '{d / 'species_stats.parquet'}'")
    cols = rel.columns
    stats = rel.fetchall()
    emb = np.load(d / "student.npz")
    by_taxon: dict[int, list[int]] = {}
    for i, t in enumerate(emb["taxon_ids"]):
        by_taxon.setdefault(int(t), []).append(i)
    photo_rows = duckdb.sql(
        f"SELECT photo_id, license, observer, observation_uuid, taxon_id FROM '{d / 'photos.parquet'}'"
    ).fetchall()
    attributions: dict[int, list[dict]] = {}
    for pid, lic, observer, obs_uuid, taxon in photo_rows:
        attributions.setdefault(int(taxon), []).append(
            {"observer": observer, "license": lic, "url": observation_url(obs_uuid)}
        )

    records = []
    for row in stats:
        r = dict(zip(cols, row))
        taxon = int(r["taxon_id"])
        months = [0] * 12
        for m in r.get("months") or []:
            if m:
                months[int(m) - 1] += 1
        idx = by_taxon.get(taxon, [])
        rec = SpeciesRecord(
            scientific_name=r["scientific_name"],
            kingdom=r["kingdom"],
            inat_taxon_id=taxon,
            gbif_key=r.get("gbif_key"),
            common_name=r.get("common_name"),
            family=r.get("family"),
            genus=r.get("genus"),
            obs_count=int(r.get("obs_count") or 0),
            months=months,
            embeddings=emb["embs"][idx].astype(np.float32) if idx else None,
            attributions=attributions.get(taxon, []),
        )
        if a.wiki:
            w = wikipedia_summary(rec.scientific_name)
            if w:
                rec.description, rec.wiki_url = w["description"], w["url"]
        records.append(rec)

    if a.text_protos:
        from .embed import text_prototypes

        need = [r for r in records if r.embeddings is None or len(r.embeddings) < 10]
        for r, z in zip(need, text_prototypes([r.scientific_name for r in need], device=a.device)):
            r.text_embedding = z

    params_path = d / "params.json"
    params = json.loads(params_path.read_text()) if params_path.exists() else {}
    params = {k: v for k, v in params.items() if k in config.DEFAULT_PARAMS}
    out = config.PACKS_DIR / f"{region.id}-v{a.version}.owpack"
    build_pack(
        out, region.id, region.name, a.version, records, curated, params,
        sources=["iNaturalist Licensed Observation Images (registry.opendata.aws/inaturalist-open-data)", "GBIF.org"],
    )
    print(out)


def cmd_region(a):
    """embed + calibrate + build for one region (assumes species/select/fetch and a trained model)."""
    cmd_embed(a)
    cmd_calibrate(a)
    cmd_build(a)


def cmd_index(a):
    from .pack import update_index

    idx = update_index(Path(a.index), Path(a.pack), a.url)
    print(f"index has {len(idx['packs'])} packs")


def main(argv=None):
    p = argparse.ArgumentParser(prog="openwild-pipeline")
    sub = p.add_subparsers(dest="cmd", required=True)

    def add(name, fn, help_):
        sp = sub.add_parser(name, help=help_)
        sp.set_defaults(fn=fn)
        return sp

    add("content-validate", cmd_content_validate, "validate content/curated/*.yaml")
    add("regions-export", cmd_regions_export, "write the app's offline region index (assets/regions.json)")
    sp = add("regions-from-naturalearth", cmd_regions_from_naturalearth, "print regions.yaml entries for every country")
    sp.add_argument("geojson")
    add("inat-download", cmd_inat_download, "download iNaturalist open-data metadata (~4.5 GB)")
    add("inat-index", cmd_inat_index, "index plants & fungi into work/inat.duckdb")
    sp = add("species", cmd_species, "GBIF species list for a region")
    sp.add_argument("region")
    sp = add("select", cmd_select, "select reference photos + region stats")
    sp.add_argument("region")
    sp.add_argument("--per-species", type=int, default=config.PHOTOS_PER_SPECIES)
    sp = add("fetch", cmd_fetch, "download selected photos")
    sp.add_argument("region")
    sp.add_argument("--workers", type=int, default=32)
    sp = add("teacher-embed", cmd_teacher_embed, "BioCLIP 2 embeddings for distillation (GPU)")
    sp.add_argument("regions", nargs="+")
    sp.add_argument("--device", default="cuda")
    sp = add("distill", cmd_distill, "train the mobile student (GPU)")
    sp.add_argument("--epochs", type=int, default=12)
    sp.add_argument("--device", default="cuda")
    sp = add("export", cmd_export, "export student to android assets as .tflite")
    sp.add_argument("--float32", action="store_true")
    for name, fn, h in (
        ("embed", cmd_embed, "student fingerprints for a region's photos"),
        ("calibrate", cmd_calibrate, "fit confidence parameters for a region"),
        ("build", cmd_build, "build the region pack"),
        ("region", cmd_region, "embed + calibrate + build"),
    ):
        sp = add(name, fn, h)
        sp.add_argument("region")
        sp.add_argument("--device", default="cuda")
        sp.add_argument("--version", type=int, default=1)
        sp.add_argument("--wiki", action="store_true", help="add Wikipedia descriptions")
        sp.add_argument("--text-protos", action="store_true", help="add teacher text prototypes for photo-poor species")
    sp = add("index", cmd_index, "add a built pack to index.json")
    sp.add_argument("pack")
    sp.add_argument("--url", required=True)
    sp.add_argument("--index", default=str(config.PACKS_DIR / "index.json"))

    a = p.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    main()
