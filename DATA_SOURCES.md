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

## Open data buckets (one-line verdicts)

| Source | Licence | What we take |
|---|---|---|
| Plants For A Future (pfaf.org), database of ~8,800 useful plants | Used with permission of PFAF | Edibility and medicinal ratings, edible parts, preparation notes and known hazards, shortened to one-liners and credited. Plants whose hazards say fatal or strongly poisonous are never shown as food. The database itself is not redistributed. |
| Kew, World Checklist of Useful Plant Species (Diazgranados et al. 2020, KNB doi:10.5063/F1CV4G34) | CC BY 4.0 | Human food / medicine / poison / fuel flags for ~40,000 species (includes PROSEA, Plant Resources of South-East Asia) |
| Wikipedia categories, English and Indonesian (edible fruits, vegetables, spices, herbs, edible and poisonous mushrooms, medicinal plants, and more) | CC BY-SA | Category membership only, mapped to species through Wikidata |
| Wikimedia Commons curated categories (poisonous plants, poisonous/deadly/psychoactive fungi, medicinal plants) | CC BY-SA | Category membership only |
| Wikidata (P225 taxon name, P105 rank, P1582 natural product of taxon, P846 GBIF key, P789 mushroom edibility) | CC0 | Names, ranks, links |

Rules: poison always beats food; rows apply only to species of the same kingdom; genus-level hits only add warnings; hand-written files override all automatic rows. All automatic rows show "not expert reviewed" in the app.
