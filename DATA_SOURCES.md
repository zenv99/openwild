# Data Sources and Licensing

OpenWild is free and non-commercial. That is not just a choice — it is a condition of the data we use. **Do not add ads, paid tiers, or sell packs.** Doing so would breach the licenses below.

## Sources

| Source | What we use | License / terms |
|---|---|---|
| iNaturalist Open Dataset (AWS, `s3://inaturalist-open-data`) | Reference photos + observation metadata (location, date, taxon) | Per-photo Creative Commons (CC0, CC BY, CC BY-NC, CC BY-SA, CC BY-NC-SA, …). We **exclude ND** (no-derivatives) licenses. iNaturalist terms prohibit training AI for commercial purposes — OpenWild is non-commercial. |
| GBIF (gbif.org) | Species lists per region, seasonality | Per-dataset CC0 / CC BY / CC BY-NC. Cite GBIF download DOIs in pack manifests. |
| BioCLIP 2 (Imageomics) | Teacher model for distillation | MIT |
| FungiTastic / Danish Fungi (optional) | Extra expert-labelled fungi photos | Check the license on its Kaggle/project page before use |
| Pl@ntNet-300K (optional) | Evaluation benchmark | Per-image CC BY-SA / CC BY-NC / CC BY-NC-SA |
| Wikipedia / Wikidata | Descriptions, common names | CC BY-SA 4.0 / CC0 |
| `content/curated/` | Edibility, toxicity, effects, survival uses | CC BY-SA 4.0, contributed by volunteers; expert review required |

## Attribution

Each region pack contains an `attributions` table listing every reference photo used: photographer, license, and source URL. The app shows these under **Settings → Credits**. Pack manifests also list source dataset citations.

## Data partnerships

We welcome additional data from iNaturalist, Pl@ntNet, herbaria, mycological societies and universities. Partner data is tracked in `regions/partners.yaml` with its license terms.

## Not legal advice

License interpretation (for example, whether model fingerprints are derivative works of photos) should be confirmed by counsel before a public release.
