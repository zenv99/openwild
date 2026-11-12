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


def data_dir(region_id: str) -> Path:
    return config.DATA_REGIONS / region_id


def student_ckpt() -> Path:
    return config.MODELS_DIR / "student.pt"


def cmd_content_validate(_):
    from .content import load_curated, load_group_rules, load_quick

    entries = load_curated(strict=True)
    quick = load_quick(strict=True)
    rules = load_group_rules(strict=True)
    print(f"OK: {len(entries)} full species entries, {len(quick)} one-liner entries, {len(rules)} genus/family rules")


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

    print(build_species_list(get_region(a.region), data_dir(a.region) / "species.csv", workers=a.workers))


def cmd_describe(a):
    """One-line Wikipedia description for every species in the region (English, then Indonesian), batched."""
    import csv

    from .content import wikipedia_intros

    src = data_dir(a.region) / "species.csv"
    names = [r["scientific_name"] for r in csv.DictReader(open(src, encoding="utf-8"))]
    rows: dict[str, dict] = {}
    remaining = names
    for lang in ("en", "id"):
        found = wikipedia_intros(remaining, lang)
        for n, w in found.items():
            if n in remaining and n not in rows:
                rows[n] = {"scientific_name": n, "description": w["description"], "wiki_url": w["url"], "lang": lang}
        remaining = [n for n in remaining if n not in rows]
    out = data_dir(a.region) / "descriptions.csv"
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "description", "wiki_url", "lang"])
        w.writeheader()
        w.writerows(rows[n] for n in names if n in rows)
    print(f"{a.region}: {len(rows)}/{len(names)} species described -> {out}")


def cmd_wcvp(a):
    """Add every accepted plant species Kew's WCVP records for the region's WGSRPD areas."""
    from .opendata import merge_wcvp_into_species_csv, wcvp_species
    from .regions import get_region

    region = get_region(a.region)
    if not region.wgsrpd:
        print(f"{a.region}: no wgsrpd areas set in regions.yaml; skipping")
        return
    rows = wcvp_species(region.wgsrpd)
    added = merge_wcvp_into_species_csv(data_dir(a.region) / "species.csv", rows)
    print(f"{a.region}: WCVP lists {len(rows)} species in {region.wgsrpd}; {added} new species added")


def cmd_wikidata(_):
    from .opendata import wikidata_fungi_edibility

    n = wikidata_fungi_edibility(config.REPO_ROOT / "content" / "quick" / "auto_wikidata_fungi.csv")
    print(f"Wikidata: {n} mushroom species with edibility")


def cmd_buckets(_):
    from .buckets import collect, write_outputs
    from .content import AUTO_GROUP_RULES

    species, genera = collect()
    n, r = write_outputs(species, genera, config.REPO_ROOT / "content" / "quick" / "auto_buckets.csv", AUTO_GROUP_RULES)
    print(f"Buckets: {n} species one-liners, {r} genus warnings")


def cmd_kew(_):
    from .kew_useful import run

    n = run(config.REPO_ROOT / "content" / "quick" / "auto_0_kew_useful.csv")
    print(f"Kew useful plants: {n} species one-liners")


def cmd_pack_index(a):
    from .publish import pack_entry, update_index

    e = pack_entry(Path(a.pack), a.url)
    idx = update_index(Path(a.index), e)
    print(f"index: {len(idx['packs'])} packs; {e['region_id']} v{e['version']} {e['size'] / 1e6:.1f} MB, "
          f"{e['species_count']} species, {e['photo_species']} with photos")


def cmd_pfaf(a):
    from .pfaf import facts, write_facts

    out = config.REPO_ROOT / "content" / "evidence" / "pfaf_facts.csv"
    print(f"PFAF facts: {write_facts(facts(Path(a.db)), out)} plants -> {out}")


def cmd_evidence(_):
    from .evidence import build, write

    rows, ledger = build(config.REPO_ROOT / "content")
    n, m = write(rows, ledger, config.REPO_ROOT / "content")
    print(f"Combined: {n} plant lines in our own words; {m} source records in the ledger")


def cmd_summary(a):
    """Coverage summary for a region: how many species, and how many have safety info at each level."""
    import csv

    from .content import load_curated, load_group_rules, load_quick, resolve

    d = data_dir(a.region)
    rows = list(csv.DictReader(open(d / "species.csv", encoding="utf-8")))
    desc = d / "descriptions.csv"
    described = sum(1 for _ in csv.DictReader(open(desc, encoding="utf-8"))) if desc.exists() else 0
    curated, quick, rules = load_curated(), load_quick(), load_group_rules()
    levels = {"species": 0, "quick": 0, "genus": 0, "family": 0, "none": 0}
    for r in rows:
        c = resolve(curated, rules, r["scientific_name"], r.get("genus"), r.get("family"), quick)
        levels[c["level"] if c else "none"] += 1
    summary = {
        "region": a.region,
        "species": len(rows),
        "plants": sum(1 for r in rows if r["kingdom"] == "Plantae"),
        "fungi": sum(1 for r in rows if r["kingdom"] == "Fungi"),
        "with_common_name": sum(1 for r in rows if r.get("common_name")),
        "with_description": described,
        "safety_info": levels,
    }
    (d / "summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


def cmd_select(a):
    from .inat import region_stats_and_photos
    from .regions import get_region

    d = region_dir(a.region)
    print(region_stats_and_photos(get_region(a.region), data_dir(a.region) / "species.csv", d, a.per_species))


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

    import csv as _csv

    descs = {}
    dpath = data_dir(a.region) / "descriptions.csv"
    if dpath.exists():
        descs = {r["scientific_name"].lower(): r for r in _csv.DictReader(open(dpath, encoding="utf-8"))}
    names = {r["scientific_name"].lower(): r for r in _csv.DictReader(open(data_dir(a.region) / "species.csv", encoding="utf-8"))}
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
        key = rec.scientific_name.lower()
        if key in names and names[key].get("common_names"):
            rec.common_names = json.loads(names[key]["common_names"])
        if key in descs:
            rec.description, rec.wiki_url = descs[key]["description"], descs[key]["wiki_url"] or None
        elif a.wiki:
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


def cmd_guide_pack(a):
    """Offline field-guide pack (no photo matching yet): every species in data/regions/<id> with names,
    descriptions and safety info. Search/browse works in the app before the photo model exists."""
    import csv

    from .content import load_curated
    from .pack import SpeciesRecord, build_pack
    from .regions import get_region

    region = get_region(a.region)
    d = data_dir(a.region)
    descs = {}
    if (d / "descriptions.csv").exists():
        descs = {r["scientific_name"].lower(): r for r in csv.DictReader(open(d / "descriptions.csv", encoding="utf-8"))}
    recs = []
    for r in csv.DictReader(open(d / "species.csv", encoding="utf-8")):
        dsc = descs.get(r["scientific_name"].lower(), {})
        recs.append(SpeciesRecord(
            scientific_name=r["scientific_name"],
            kingdom=r.get("kingdom") or "Plantae",
            gbif_key=int(r["gbif_key"]) if (r.get("gbif_key") or "").isdigit() else None,
            common_name=r.get("common_name") or None,
            common_names=json.loads(r["common_names"]) if r.get("common_names") else {},
            family=r.get("family") or None,
            genus=r.get("genus") or None,
            obs_count=int(float(r.get("gbif_count") or 0)),
            description=dsc.get("description") or None,
            wiki_url=dsc.get("wiki_url") or None,
        ))
    out = Path(a.out) if a.out else config.PACKS_DIR / f"{region.id}-v{a.version}.owpack"
    build_pack(out, region.id, region.name, a.version, recs, load_curated(), guide_only=True,
               sources=["GBIF.org", "Kew WCVP (CC BY)", "Wikipedia (CC BY-SA)", "Wikidata (CC0)"])
    print(out, f"{out.stat().st_size / 1e6:.1f} MB")


def cmd_free_model(_):
    from .freemodel import export

    export()


def cmd_photos(a):
    """Download reference photos for a pack build.

    When the region has a photo manifest (Bali's weekly backfill), download those pinned URLs and
    refetch only covered species that have no direct image URL. Pending species are left for
    `photo-batch`, so a pack rebuild does not crawl the whole gap. Regions without a manifest keep
    the original full GBIF crawl.
    """
    from .gbif_photos import fetch_region_photos
    from .photo_batch import fetch_recorded, manifest_path, status_path

    d = data_dir(a.region)
    out = config.WORK / "photos_gbif" / a.region
    if manifest_path(d).exists() and status_path(d).exists():
        fetch_recorded(d / "species.csv", out, manifest_path(d), status_path(d),
                       per_species=a.per_species, workers=a.workers)
        return
    fetch_region_photos(d / "species.csv", out, per_species=a.per_species, workers=a.workers,
                        max_species=a.max_species)


def cmd_photo_baseline(a):
    """Record the photos already inside a published pack so later builds do not depend on recrawling."""
    from .photo_batch import baseline_from_pack, manifest_path, pin_covered_without_file, pin_occurrence_media, status_path

    d = data_dir(a.region)
    stats = baseline_from_pack(Path(a.pack), manifest_path(d), status_path(d))
    print(f"{a.region}: pinned {stats['pinned_photos']} iNaturalist photos for {stats['pinned_species']} species; "
          f"{stats['have_species']} marked have; {stats['refetch_on_publish']} covered species had no iNaturalist file URL")
    if not a.skip_occurrence_resolve:
        extra = pin_occurrence_media(Path(a.pack), manifest_path(d), status_path(d))
        print(f"{a.region}: pinned {extra['occurrence_photos']} images from GBIF occurrence pages "
              f"for {extra['occurrence_species']} of those species")
        covered = pin_covered_without_file(d / "species.csv", manifest_path(d), status_path(d))
        print(f"{a.region}: pinned {covered['photos']} further images for {covered['species']} "
              f"covered species that had no file URL")


def cmd_photo_batch(a):
    """Add openly licensed photos for the next handful of species that still lack one."""
    from datetime import datetime, timezone

    from .content import load_curated, load_group_rules, load_quick
    from .photo_batch import run_batch

    batch_id = a.batch or datetime.now(timezone.utc).strftime("%Y%m%d")
    summary = run_batch(
        data_dir(a.region) / "species.csv", data_dir(a.region),
        limit=a.limit, max_tier=a.max_tier, per_species=a.per_species, batch_id=batch_id,
        curated=load_curated(), quick=load_quick(), rules=load_group_rules(),
    )
    return summary


def cmd_photo_pack(a):
    """Field guide + photo fingerprints (free DINOv2 model) for every species with reference photos."""
    import csv
    from collections import defaultdict

    import numpy as np

    from .calibrate import calibrate
    from .content import load_curated
    from .freemodel import embed_paths
    from .pack import SpeciesRecord, build_pack
    from .regions import get_region

    region = get_region(a.region)
    d = data_dir(a.region)
    photos = list(csv.DictReader(open(config.WORK / "photos_gbif" / a.region / "photos.csv", encoding="utf-8")))
    embs = embed_paths([Path(p["path"]) for p in photos])
    by_species: dict[str, list[int]] = defaultdict(list)
    for i, p in enumerate(photos):
        by_species[p["scientific_name"].lower()].append(i)

    taxa = np.array([hash(p["scientific_name"].lower()) for p in photos])
    params = {}
    try:
        cal = calibrate(embs, taxa)
        print("calibration:", cal)
        params = {k: v for k, v in cal.items() if k in config.DEFAULT_PARAMS}
    except Exception as e:  # noqa: BLE001
        print(f"calibration skipped: {e!r}")

    def display_order(idx: list[int]) -> list[int]:
        """Cover first: the most typical field photo (closest to the species' average fingerprint).
        Then pick for variety: each next photo is the one least like those already chosen, so swiping shows
        different views (flower, leaf, whole plant) rather than near-duplicates. Herbarium sheets go last."""
        if not idx:
            return idx
        e = embs[idx].astype(np.float32)
        e = e / (np.linalg.norm(e, axis=1, keepdims=True) + 1e-9)
        typical = e @ e.mean(axis=0)
        field = [photos[i].get("kind", "field") != "specimen" for i in idx]
        pool = [j for j in range(len(idx)) if field[j]] or list(range(len(idx)))
        chosen = [max(pool, key=lambda j: typical[j])]
        rest = [j for j in pool if j not in chosen]
        while rest:
            sims = e[rest] @ e[chosen].T
            nxt = rest[int(np.argmin(sims.max(axis=1)))]
            chosen.append(nxt)
            rest.remove(nxt)
        chosen += [j for j in range(len(idx)) if j not in chosen]
        return [idx[j] for j in chosen]

    descs = {}
    if (d / "descriptions.csv").exists():
        descs = {r["scientific_name"].lower(): r for r in csv.DictReader(open(d / "descriptions.csv", encoding="utf-8"))}
    recs = []
    for r in csv.DictReader(open(d / "species.csv", encoding="utf-8")):
        key = r["scientific_name"].lower()
        idx = by_species.get(key, [])
        dsc = descs.get(key, {})
        recs.append(SpeciesRecord(
            scientific_name=r["scientific_name"],
            kingdom=r.get("kingdom") or "Plantae",
            gbif_key=int(r["gbif_key"]) if (r.get("gbif_key") or "").isdigit() else None,
            common_name=r.get("common_name") or None,
            common_names=json.loads(r["common_names"]) if r.get("common_names") else {},
            family=r.get("family") or None,
            genus=r.get("genus") or None,
            obs_count=int(float(r.get("gbif_count") or 0)),
            description=dsc.get("description") or None,
            wiki_url=dsc.get("wiki_url") or None,
            embeddings=embs[idx] if idx else None,
            attributions=[{"observer": photos[i]["observer"], "license": photos[i]["license"], "url": photos[i]["page"]} for i in idx],
            photos=[{"path": photos[i]["path"], "observer": photos[i]["observer"], "license": photos[i]["license"],
                     "url": photos[i]["page"]} for i in display_order(idx)],
        ))
    out = Path(a.out) if a.out else config.PACKS_DIR / f"{region.id}-v{a.version}.owpack"
    build_pack(out, region.id, region.name, a.version, recs, load_curated(), params=params, guide_only=True,
               min_photos_for_image_protos=2, photos_per_species=a.photos_shown,
               sources=["GBIF.org occurrence media (CC licences)", "Kew WCVP (CC BY)", "Wikipedia (CC BY-SA)", "Wikidata (CC0)"])
    with_photos = sum(1 for r in recs if r.embeddings is not None and len(r.embeddings) >= 2)
    print(out, f"{out.stat().st_size / 1e6:.1f} MB; {with_photos} species identifiable by photo")


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
    sp = add("species", cmd_species, "GBIF species list (with common names) for a region -> data/regions/<id>/")
    sp.add_argument("region")
    sp.add_argument("--workers", type=int, default=8)
    sp = add("describe", cmd_describe, "one-line Wikipedia descriptions for every species in a region")
    sp.add_argument("region")
    sp.add_argument("--workers", type=int, default=8)
    sp = add("wcvp", cmd_wcvp, "add Kew WCVP plant species for the region's WGSRPD areas")
    sp.add_argument("region")
    add("wikidata", cmd_wikidata, "auto one-liners: mushroom edibility from Wikidata (CC0)")
    sp = add("pack-index", cmd_pack_index, "add/replace a pack in the downloads index.json")
    sp.add_argument("--pack", required=True)
    sp.add_argument("--index", required=True)
    sp.add_argument("--url", required=True)
    add("evidence", cmd_evidence, "combine PFAF facts + Kew + Wikipedia into original one-liners with a source ledger")
    sp = add("pfaf", cmd_pfaf, "extract facts (no text) from a Plants For A Future database file (used with permission)")
    sp.add_argument("--db", required=True, help="path to the PFAF SQLite export")
    add("kew", cmd_kew, "auto one-liners: Kew World Checklist of Useful Plant Species (CC BY 4.0)")
    add("buckets", cmd_buckets, "auto one-liners: edible/poisonous/medicinal lists from Wikipedia categories + Wikidata")
    sp = add("summary", cmd_summary, "coverage summary for a region -> data/regions/<id>/summary.json")
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
    add("free-model", cmd_free_model, "export the free DINOv2-small fingerprint model to android assets (CPU)")
    sp = add("photos", cmd_photos, "download reference photos for a pack build (manifest if present, else GBIF)")
    sp.add_argument("region")
    sp.add_argument("--per-species", type=int, default=8)
    sp.add_argument("--workers", type=int, default=16)
    sp.add_argument("--max-species", type=int, default=None)
    sp = add("photo-baseline", cmd_photo_baseline, "pin photos already shipped in a pack into data/regions/<id>/")
    sp.add_argument("region")
    sp.add_argument("--pack", required=True, help="path to an .owpack whose photo credits should be pinned")
    sp.add_argument("--skip-occurrence-resolve", action="store_true",
                    help="do not look up image files for gbif.org/occurrence credits")
    sp = add("photo-batch", cmd_photo_batch, "weekly batch: openly licensed photos for the next missing species")
    sp.add_argument("region")
    sp.add_argument("--limit", type=int, default=160, help="species to look up this run (default 160, about a week)")
    sp.add_argument("--max-tier", type=int, default=None, help="only species at this priority tier or higher (0 = deadly)")
    sp.add_argument("--per-species", type=int, default=2)
    sp.add_argument("--batch", default=None, help="batch id recorded on new rows (default UTC date)")
    sp = add("photo-pack", cmd_photo_pack, "build a region pack with photo fingerprints (free model, CPU)")
    sp.add_argument("region")
    sp.add_argument("--version", type=int, default=2)
    sp.add_argument("--out", default=None)
    sp.add_argument("--photos-shown", type=int, default=3, help="photos per species stored for display")
    sp = add("guide-pack", cmd_guide_pack, "offline field-guide pack from data/regions/<id> (no photo model needed)")
    sp.add_argument("region")
    sp.add_argument("--version", type=int, default=1)
    sp.add_argument("--out", default=None)
    sp = add("index", cmd_index, "add a built pack to index.json")
    sp.add_argument("pack")
    sp.add_argument("--url", required=True)
    sp.add_argument("--index", default=str(config.PACKS_DIR / "index.json"))

    a = p.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    main()
