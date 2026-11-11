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
| Plants For A Future (pfaf.org), database of ~8,800 useful plants | Used with permission of PFAF | Facts only (content/evidence/pfaf_facts.csv): parts eaten, raw or cooked, kinds of hazard, food/medicine ratings, survival uses, link to the PFAF page. No PFAF text, selection or structure is reproduced; the database itself is not redistributed. |
| Kew, World Checklist of Useful Plant Species (Diazgranados et al. 2020, KNB doi:10.5063/F1CV4G34) | CC BY 4.0 | Human food / medicine / poison / fuel flags for ~40,000 species (includes PROSEA, Plant Resources of South-East Asia) |
| Wikipedia categories, English and Indonesian (edible fruits, vegetables, spices, herbs, edible and poisonous mushrooms, medicinal plants, and more) | CC BY-SA | Category membership only, mapped to species through Wikidata |
| Wikimedia Commons curated categories (poisonous plants, poisonous/deadly/psychoactive fungi, medicinal plants) | CC BY-SA | Category membership only |
| Wikidata (P225 taxon name, P105 rank, P1582 natural product of taxon, P846 GBIF key, P789 mushroom edibility) | CC0 | Names, ranks, links |

**How the lines in the app are written.** `cli evidence` combines the facts from PFAF, Kew and Wikipedia/Commons for each plant and writes one line in OpenWild's own words (content/quick/auto_combined.csv), saying how many independent sources confirm a food use. Every claim, per source, with a link, is kept in content/evidence/ledger.csv. Kew's "poisons" category means a plant is *used as* a poison (fish or insect poison), so on its own it does not mark a known food plant as dangerous.

Photos come only from GBIF occurrence media under Creative Commons licences (CC0, CC BY, CC BY-SA, CC BY-NC, CC BY-NC-SA), with the photographer and licence shown under every photo. No PFAF images are used.

Rules: poison always beats food; a food claim from one source, or without documented edible parts, never outweighs a poison flag; anything any source says can kill is never shown as food; rows apply only to species of the same kingdom; genus-level hits only add warnings; hand-written files override all automatic rows. All automatic rows show "not expert reviewed" in the app.
