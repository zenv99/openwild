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
