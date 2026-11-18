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

## Region packs and where photos come from

Country packs are the default for everyone. A finer pack (Bali inside Indonesia, a US state inside the United States) is optional and is not a replacement. The same scientific name is scored once on the phone when both packs are switched on. Pack files stay separate so either one can be deleted on its own. Region data is never deleted from the project because a finer pack exists.

Photos for a region are collected in resumable batches (`select_photo_batch` in `commercial_photos.py`). A run that is capped at a few thousand species records what it already tried and the next run continues down the list. The scheduler keeps walking while `photo-progress.json` says `more`, and it waits to photograph a state or Bali-sized area until the country pack already has photos. Reference photos that claim to show a named species come from research-grade iNaturalist observations, Wikimedia Commons, and GBIF media. Needs-ID observations are not used as a photo of that species. Every packed photo keeps an open licence and a credit. No-derivatives licences, Plants For A Future images, and tropical.theferns.info are never used.

Plants For A Future facts already in `content/evidence/pfaf_facts.csv` were ingested with permission, as text facts rather than pages. They are left in place for the owner to keep or remove. This pass did not add any PFAF material and did not fetch pfaf.org or tropical.theferns.info.

Fungi are included from GBIF alongside plants. In the published names-only packs checked for this audit, fungi are a small share in dry regions (69 of 1,265 species in the UAE) and a large share in temperate Europe (4,951 of 13,790 in Germany). Edibility for most of those fungi is still unknown.

## Not legal advice

License interpretation (for example, whether model fingerprints are derivative works of photos) should be confirmed by counsel before a public release.

## Open data buckets (one-line verdicts)

| Source | Licence | What we take |
|---|---|---|
| Plants For A Future (pfaf.org), database of ~8,800 useful plants | Used with permission of PFAF | Facts only (content/evidence/pfaf_facts.csv): parts eaten, raw or cooked, kinds of hazard, food/medicine ratings, survival uses, link to the PFAF page. No PFAF text, selection or structure is reproduced; the database itself is not redistributed. The file is left as-is. Nothing new is fetched from pfaf.org or tropical.theferns.info. Keeping the file is Zen's decision. |
| Kew, World Checklist of Useful Plant Species (Diazgranados et al. 2020, KNB doi:10.5063/F1CV4G34) | CC BY 4.0 | Human food / medicine / poison / fuel flags for ~40,000 species (includes PROSEA, Plant Resources of South-East Asia) |
| Wikipedia categories, English and Indonesian (edible fruits, vegetables, spices, herbs, edible and poisonous mushrooms, medicinal plants, and more) | CC BY-SA | Category membership only, mapped to species through Wikidata |
| Wikimedia Commons curated categories (poisonous plants, poisonous/deadly/psychoactive fungi, medicinal plants) | CC BY-SA | Category membership only |
| Wikidata (P225 taxon name, P105 rank, P1582 natural product of taxon, P846 GBIF key, P789 mushroom edibility) | CC0 | Names, ranks, links |

**How the lines in the app are written.** `cli evidence` combines the facts from PFAF, Kew and Wikipedia/Commons for each plant and writes one line in OpenWild's own words (content/quick/auto_combined.csv), saying how many independent sources confirm a food use. Every claim, per source, with a link, is kept in content/evidence/ledger.csv. Kew's "poisons" category means a plant is *used as* a poison (fish or insect poison), so on its own it does not mark a known food plant as dangerous.

Photos packed into the app, and the fingerprints built from them, use Creative Commons licences that permit OpenWild's free, non-commercial use: **CC0, CC BY, CC BY-SA, CC BY-NC and CC BY-NC-SA**, including iNaturalist observers' photos (each under its own licence; iNaturalist's terms only restrict *commercial* AI training). **No-derivatives (ND)** licences, Plants For A Future images and tropical.theferns.info are never used. The photographer, licence and source link are stored on each photo and shown in the app. Up to 3 photos are kept per growth stage (seedling, leaves, flower, fruit, close-up); stage labels come only from the source (Commons category or file title, GBIF/iNaturalist life stage or "flowering"/"fruiting" annotation) and a missing stage stays missing. The policy is one switch, `NONCOMMERCIAL_APP` in `pipeline/openwild_pipeline/config.py`: turning it off rebuilds every pack from commercial-ok photos only. Country packs that still have gaps are filled in small weekly batches (`photo-batch`, see REGION_QUEUE.md), not by one crawl of the whole list. Bali's photo store is kept and folded into the next Indonesia build; it is not a separate download.

Rules: poison always beats food; a food claim from one source, or without documented edible parts, never outweighs a poison flag; anything any source says can kill is never shown as food; rows apply only to species of the same kingdom; genus-level hits only add warnings; hand-written files override all automatic rows. All automatic rows show "not expert reviewed" in the app.

## Landing-page photos

| Photo | Author | Licence | Source |
|---|---|---|---|
| Fly agaric (*Amanita muscaria*) on autumn leaves | Dominicus Johannes Bergsma | CC BY-SA 4.0 | https://commons.wikimedia.org/wiki/File:Vliegenzwam_(Amanita_muscaria)._Locatie_De_Famberhorst._27-09-2020_(d.j.b.).jpg |
| Tropical fruit | Peggy Greb, USDA Agricultural Research Service | Public domain | https://commons.wikimedia.org/wiki/File:ARS_tropical_fruit_no_labels.jpg |

Fetched and licence-checked by `.github/workflows/hero-images.yml` (refuses anything that isn't public domain, CC0, CC BY or CC BY-SA). The opening screen is the forest artwork (`landing_forest.webp`), original art created for Open Wild, the same picture as the home screen. The fly agaric photograph is not shown there, so that credit line is not on the opening screen. The file stays in the app for the older morph sequence, which still shows this credit.

## Community photos (the long-term library)

OpenWild users can add their own plant photos from any species page ("Add your photos"). They choose the name
to be credited and release the photos under **CC BY 4.0**. The app re-encodes the photos (removing GPS
position, time and camera details), saves them to Pictures/OpenWild and opens the "Plant photos" form on
GitHub, where the user attaches and submits them. A maintainer checks each submission (right plant, the
person's own work, nothing private) and adds the `approved` label; the `community-photos` workflow then
strips metadata again, stores the photos under `content/community/<region>/` with the credit, thanks the
contributor and rebuilds the pack. Community photos are shown first and credited "Photo by NAME · OpenWild
community". Over time they replace stock photos that carry non-commercial terms.

Plants that still need photos are marked "Photos wanted" in the app, and listed in `docs/photos-wanted/`.
