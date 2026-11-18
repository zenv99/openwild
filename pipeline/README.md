# OpenWild Pipeline

Builds the on-device model and the region packs. Everything here is free to run.

## Where to run it

| Step | Needs | Free option |
|---|---|---|
| Metadata download + indexing | ~25 GB disk, 16 GB RAM | Any laptop/desktop, or a Kaggle notebook |
| Photo download | Disk (~40 KB per photo; ~2 GB per 50k photos) | Same |
| Teacher embedding + distillation | GPU | Kaggle Notebooks (T4/P100, ~30 GPU h/week) or Google Colab |
| Region embedding, calibration, pack build | CPU fine, GPU faster | Same |

Set `OPENWILD_WORK=/path/with/space` to choose where large files go (default: `./work`, git-ignored).

## One-time setup

```bash
cd pipeline
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m openwild_pipeline.cli content-validate      # check safety content
python -m openwild_pipeline.cli inat-download          # ~4.5 GB from the public AWS bucket
python -m openwild_pipeline.cli inat-index             # builds work/inat.duckdb
```

## Per region: data

```bash
python -m openwild_pipeline.cli species PH             # GBIF: plant + fungus species recorded in the Philippines
python -m openwild_pipeline.cli select PH              # pick up to 40 licensed reference photos per species
python -m openwild_pipeline.cli fetch PH               # download them (resumable)
```

## Model (once, then occasionally)

Train the student on photos from as many regions as you've fetched — more variety is better.

```bash
python -m openwild_pipeline.cli teacher-embed WORLD PH ID TH MY AU GB DE US-CA
python -m openwild_pipeline.cli distill --epochs 12
python -m openwild_pipeline.cli export                 # -> android/app/src/main/assets/embedder.tflite
```

`export` prints a parity score between PyTorch and LiteRT; it should be above 0.99.

**If you retrain the model, bump `MODEL_ID` in `config.py` and `Embedder.MODEL_ID` in the app, and rebuild every pack.** Packs and model must match.

## Per region: pack

```bash
python -m openwild_pipeline.cli region PH --version 1 --wiki --text-protos
python -m openwild_pipeline.cli index work/packs/PH-v1.owpack \
    --url https://github.com/<org>/openwild/releases/download/packs/PH-v1.owpack
```

Upload `PH-v1.owpack` and `index.json` to a GitHub Release (tag `packs`). The app reads `index.json` from
`https://github.com/<org>/openwild/releases/download/packs/index.json` (set in `android/app/build.gradle.kts`).

## Bali photos, in weekly batches

Pack `ID-BA` version `2026111202` has a photo for 1,380 of 4,383 species. A later full crawl is the wrong way to close that gap: the bulk GBIF fetch dropped most species (including oleander, glory lily and guava, which have thousands of Creative Commons field photos), and running it again can replace the pack with a different random subset.

What is checked in:

- `data/regions/ID-BA/photo_status.csv` — `have` (1,380 already in pack `2026111202`), `found` (added by a batch), `none` (looked up; no open licence), `error` (left in the queue).
- `data/regions/ID-BA/photo_manifest.csv` — direct image URL, photographer, licence, and source page. Every species already in the pack has a file URL here (iNaturalist, or the GBIF media file behind a specimen page), so a rebuild does not search the whole backlog.

The first batch (`20261112`) added **35 species / 70 field photos**, all from iNaturalist under CC0, CC BY, CC BY-SA, CC BY-NC or CC BY-NC-SA, with the photographer's name. Those are the deadly, serious, and curated survival species that the published pack had missed. None of that shortlist lacked an open field photo.

Each Monday the **Bali photo batch** workflow looks up the next 160 missing species. After this batch, **2,968** species are still unattempted, so 160 a week is about **19 weeks (4.5 months)**, finishing around April 2027 if the Monday job starts on 16 November 2026. `--limit 230` is closer to 3 months if the APIs stay comfortable; 160 is the default. Priority is deadly and serious species, then survival and edible species already in the safety content, then the rest of the list, common names first. Sources, in order: iNaturalist field photos, GBIF field photos, Wikimedia Commons, and a herbarium sheet only if nothing else exists. Licences: CC0, CC BY, CC BY-SA, CC BY-NC, CC BY-NC-SA. ND is refused. Plants For A Future and tropical.theferns.info are not used.

```bash
python -m openwild_pipeline.cli photo-batch ID-BA --limit 160
# first safety shortlist only:
python -m openwild_pipeline.cli photo-batch ID-BA --limit 40 --max-tier 2 --batch 20261112
```

Publishing so phones can update (no new APK):

1. Actions → **Photo model + region photo pack**.
2. `region` = `ID-BA`, `per_species` = `8`, **`export_model` = `false`** (true rebuilds the embedder and triggers an APK).
3. The workflow downloads the pinned image URLs (it does not crawl the species still queued), fingerprints them, and uploads `ID-BA.owpack` plus `index.json` to the `packs` release. The version is the UTC hour (`YYYYMMDDHH`). The next publish should move **photo_species from 1,380 to 1,415** (the 35 species above), as long as those URLs still download. A URL that has gone away is fetched again from GBIF for that species only.
4. In the app, Packs shows an update when that version is newer. Update installs it after a SHA-256 check. The library stays whatever packs the release index lists; this does not add other regions.

Species recorded as `none` stay without a photo: no allowed licence turned up on iNaturalist, GBIF, or Commons.

## Updating data

New photos or partner data → `select`, `fetch`, `region --version N+1`, `index`, upload. No app update needed.

## Adding regions

Edit `regions/regions.yaml`, or generate every country from Natural Earth's free Admin-0 file:

```bash
python -m openwild_pipeline.cli regions-from-naturalearth ne_10m_admin_0_countries.geojson > /tmp/all.yaml
python -m openwild_pipeline.cli regions-export        # refresh the app's bundled region index
```

## Tests

```bash
pytest
```

## Status

Tested here: confidence scoring, prototypes/quantisation, calibration, pack building, content validation,
region lookup. Written but not yet run end-to-end (needs internet + GPU): metadata download/indexing,
GBIF queries, photo fetching, distillation, LiteRT export. Expect small fixes on first real run.
