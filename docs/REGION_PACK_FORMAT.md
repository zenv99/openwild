# Region Pack Format (schema version 1)

A region pack is a single SQLite 3 database, opened read-only by the app. Filename: `<region_id>-v<version>.owpack` (SQLite file).

## Tables

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE species (
  id               INTEGER PRIMARY KEY,   -- pack-local id
  inat_taxon_id    INTEGER,
  gbif_key         INTEGER,
  scientific_name  TEXT NOT NULL,
  common_name      TEXT,                  -- best English (or local) common name
  common_names     TEXT,                  -- JSON object {"en": [...], "id": [...], ...}
  kingdom          TEXT NOT NULL,         -- 'Plantae' | 'Fungi'
  family           TEXT,
  genus            TEXT,
  obs_count        INTEGER NOT NULL DEFAULT 0,  -- observations in region (prior)
  months           BLOB,                  -- 12 bytes, relative frequency per month 0..255
  description      TEXT,
  wiki_url         TEXT
);

CREATE TABLE prototype (
  id          INTEGER PRIMARY KEY,
  species_id  INTEGER NOT NULL REFERENCES species(id),
  vec         BLOB NOT NULL,              -- D signed int8 values
  scale       REAL NOT NULL,              -- dequantised value = int8 * scale
  kind        TEXT NOT NULL DEFAULT 'image'  -- 'image' | 'text'
);
CREATE INDEX prototype_species ON prototype(species_id);

CREATE TABLE content (
  species_id  INTEGER PRIMARY KEY REFERENCES species(id),
  json        TEXT NOT NULL               -- see CONTENT_SCHEMA.md
);

CREATE TABLE lookalike (
  species_id            INTEGER NOT NULL REFERENCES species(id),
  lookalike_species_id  INTEGER,          -- NULL if lookalike not in this pack
  lookalike_name        TEXT NOT NULL,
  danger                TEXT NOT NULL     -- 'deadly' | 'toxic' | 'psychoactive' | 'harmless'
);

CREATE TABLE attribution (
  id          INTEGER PRIMARY KEY,
  species_id  INTEGER NOT NULL,
  observer    TEXT,
  license     TEXT NOT NULL,
  url         TEXT NOT NULL
);
```

## `meta` keys

| Key | Example | Meaning |
|---|---|---|
| `schema_version` | `1` | Pack schema |
| `region_id` | `PH` | Region id from `regions/regions.yaml` |
| `region_name` | `Philippines` | Display name |
| `pack_version` | `3` | Increments on every rebuild |
| `built_at` | `2026-11-11T00:00:00Z` | UTC |
| `model_id` | `ow-mnv4m-768-v1` | Must equal the app's embedder model id |
| `embedding_dim` | `768` | D |
| `temperature` | `0.03` | Softmax temperature (CONFIDENCE.md) |
| `photo_alpha` | `0.6` | Multi-photo fusion exponent |
| `prior_beta` | `0.5` | Weight of where/when prior |
| `open_set_threshold` | `0.30` | Min mean cosine similarity for a match |
| `high_threshold` | `0.85` | Fused probability for High tier |
| `medium_threshold` | `0.50` | Fused probability for Medium tier |
| `text_proto_penalty` | `0.05` | Subtracted from text-prototype similarity |
| `sources` | JSON array | Dataset citations / GBIF DOIs |

## `index.json` (hosted, lists all packs)

```json
{
  "schema": 1,
  "model_id": "ow-mnv4m-768-v1",
  "packs": [
    {
      "region_id": "PH",
      "name": "Philippines",
      "version": 3,
      "url": "https://github.com/<org>/openwild/releases/download/packs-PH-v3/PH-v3.owpack",
      "sha256": "…",
      "size": 14200000,
      "species_count": 4120,
      "photos_url": null,
      "photos_sha256": null,
      "photos_size": 0
    }
  ]
}
```

The app verifies `sha256` before installing a pack and rejects packs whose `meta.model_id` differs from its embedder.
