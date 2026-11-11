# Architecture

```
                 ONE-TIME / PERIODIC (pipeline, free GPU)                     ON THE PHONE (offline)
┌───────────────────────────────────────────────────────────┐      ┌──────────────────────────────────────┐
│ iNat Open Data metadata ──┐                               │      │ Camera / gallery (1–5 photos)        │
│ GBIF species per region ──┼─► select species + photos    │      │        │                              │
│                           │        │                      │      │        ▼                              │
│                           │        ▼                      │      │ Embedder (LiteRT, ~10–25 MB)          │
│ BioCLIP 2 (teacher) ──► distill ─► student embedder ──────┼──────┼─► fingerprint per photo               │
│                                    │   (embedder.tflite)  │      │        │                              │
│                                    ▼                      │      │        ▼                              │
│        reference photos ─► fingerprints ─► prototypes     │      │ Matcher: nearest prototypes in pack   │
│                                    │                      │      │        │                              │
│ content/curated + Wikipedia ───────┤                      │      │        ▼                              │
│                                    ▼                      │      │ Confidence: fuse photos + where/when  │
│                    region pack (SQLite)  ─► GitHub Release┼──────┼─► Tier + score + safety gating        │
└───────────────────────────────────────────────────────────┘      └──────────────────────────────────────┘
```

## Why "fingerprint matching" instead of a classifier

A classifier must be retrained whenever species are added. Here the model only produces a fingerprint (an embedding vector); identification is a nearest-neighbour search against prototypes stored in the region pack.

- **Add or fix data** → rebuild the pack. No app release, no retraining.
- **New species** → add reference photos (or, if none exist yet, a text prototype from the teacher's text encoder, flagged lower-trust).
- **Region packs stay small** → only local species' prototypes ship.

## The model

- **Teacher:** BioCLIP 2 (ViT-L/14, MIT license, trained on ~214M organism images). Too large for phones; used only in the pipeline.
- **Student:** MobileNetV4-Conv-Medium (timm) + linear projection to the teacher's 768-d embedding space, trained to reproduce teacher image embeddings (cosine distillation loss).
- **Export:** LiteRT `.tflite`, NHWC float input `[1,224,224,3]` in `[0,1]`, normalisation inside the graph, L2-normalised `[1,768]` output.
- The student's `model_id` is recorded in every pack; the app refuses packs built for a different model.

Because the student lives in the teacher's embedding space, teacher **text** embeddings (e.g. "a photo of *Amanita phalloides*") can act as fallback prototypes for species with few or no photos.

## Region packs

One SQLite file per region (format: [REGION_PACK_FORMAT.md](REGION_PACK_FORMAT.md)), plus an optional thumbnails pack. Hosted on GitHub Releases; listed in `index.json`.

Regions start as countries, with large countries split into first-level subdivisions (`regions/regions.yaml`). The app picks the region from GPS using bundled bounding boxes — no network needed.

## Confidence

See [CONFIDENCE.md](CONFIDENCE.md). Calibration parameters live in the pack manifest so they can be tuned per region without an app update.

## Safety

See [../SAFETY.md](../SAFETY.md). The app's `SafetyPolicy` decides what is shown for a given tier and content record.

## Size budget

| Item | Size |
|---|---|
| APK (code + model, int8) | ~15–30 MB |
| Region pack (4,000 species × ~4 prototypes × 768 B + content) | ~12–20 MB |
| Optional thumbnails pack | ~10–25 MB |
