# Region queue

The weekly data bot fills one region per week, top to bottom. For each region it:
1. runs the **Region data** GitHub Action (every GBIF plant & fungus species + names + Wikipedia one-liners),
2. writes bulk one-liners (edible / poisonous / benefit) in `content/quick/<region>_*.csv` for as many of that region's species as it can verify, most-common species first,
3. ticks the box below and updates `COVERAGE.md`.

Then it moves to the next region. Already-filled regions get topped up once the queue is done.

- [ ] ID-BA — Bali, Indonesia (in progress: species list + 275 one-liners)
- [ ] ID — Indonesia (rest)
- [ ] PH — Philippines
- [ ] TH — Thailand
- [ ] MY — Malaysia
- [ ] VN — Vietnam
- [ ] SG — Singapore
- [ ] KH — Cambodia
- [ ] LA — Laos
- [ ] MM — Myanmar
- [ ] TL — Timor-Leste
- [ ] PG — Papua New Guinea
- [ ] AU — Australia
- [ ] NZ — New Zealand
- [ ] US-HI — Hawaii
- [ ] IN — India
- [ ] LK — Sri Lanka
- [ ] JP — Japan
- [ ] TW — Taiwan
- [ ] KR — South Korea
- [ ] CN — China
- [ ] US-CA — California
- [ ] US — United States
- [ ] CA — Canada
- [ ] MX — Mexico
- [ ] CR — Costa Rica
- [ ] CO — Colombia
- [ ] BR — Brazil
- [ ] PE — Peru
- [ ] GB — United Kingdom
- [ ] DE — Germany
- [ ] FR — France
- [ ] ES — Spain
- [ ] IT — Italy
- [ ] ZA — South Africa
- [ ] KE — Kenya
- [ ] (all remaining regions in regions/regions.yaml)

## Bali photos (separate queue, 2026-11 to about 2027-04)

The region queue above fills species lists and safety lines. Photos are a second queue, Bali only, so the gap of about 3,003 species is not fetched in one run.

Published pack `2026111202`: **1,380 / 4,383** species have a photo. Coverage was about 30% in every abundance band, including common deadly and edible plants that do have Creative Commons field photos. The bulk downloader dropped them; it did not prove those species lack a picture.

- **This change:** the 1,380 species that already have a photo are pinned to a file URL. The first batch then looked up the 35 deadly, serious, and curated survival species the pack had missed: **35/35 now have an iNaturalist field photo** (70 photos, photographer and licence on each row).
- **Every Monday** (`.github/workflows/photo-batch.yml`): the next **160** of the remaining **2,968** species. iNaturalist, then GBIF, then Wikimedia Commons; herbarium only as a last resort; CC0 / CC BY / CC BY-SA / CC BY-NC / CC BY-NC-SA only. About **19 weeks (4.5 months)**, through roughly April 2027. `--limit 230` is closer to 3 months if the APIs stay comfortable; 160 is the default.
- **Order:** deadly names, serious names, curated food plants, toxic one-liners, edible one-liners, deadly genera, other safety lines, then everything else with the most-recorded species first.
- **When phones should see the new photos:** dispatch **Photo model + region photo pack** with `export_model` false. That publishes a new `ID-BA.owpack` and `index.json` on the `packs` release (version `YYYYMMDDHH`). Packs → Update downloads it. Doing that about once a month is enough; the weekly job only commits the manifest. Details and commands are in `pipeline/README.md`.

`none` in `data/regions/ID-BA/photo_status.csv` means that week's lookup found no openly licensed photo. Those species are not retried every week.
