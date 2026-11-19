# Brains, Indonesia pack

Measured on this machine (4-core CPU, LiteRT with XNNPACK) in November 2026.
The leave-one-out hides each reference photo and ranks species by the closest remaining photo,
the same protocol as ACCURACY.md. Good labelled photos, so treat it as an upper bound.

## Leave-one-out

| Brain | Crops | Top-1 | Top-3 | Photos | On-device size | Licence | Latency (this CPU) |
|---|---|---|---|---|---|---|---|
| DINOv2 ViT-S/14, one crop | 1 | 52.5% | 64.6% | 38,371 | 22.8 MB, built in | Apache-2.0 | 24.5 ms / crop |
| DINOv2 ViT-S/14, several crops | 4 | 55.6% | 67.6% | 38,371 | same file | Apache-2.0 | about 4× one crop |
| DINOv2 ViT-B/14 | 1 | 63.1% | 75.7% | 700 species, not this full set | not shipped | Apache-2.0 | 1.83 s / photo, unquantized torch |
| BioCLIP 2, image prototypes | 1 | 74.4% | 86.2% | 38,371 | 309 MB model + 27 MB region file | MIT | 106 ms / crop, quantized |
| BioCLIP 2, name only, no image boost | | 48.7% top-1, 72.6% top-5 | | half of species with photos | same model | MIT | text encoder, not on the phone |
| BioCLIP 2 + DINOv2 S, z-scored fusion | 1 | 73.1% | 84.4% | 38,371 | both files | | slower than either alone |

BioCLIP 2 leave-one-out is on the 38,371 reference photos from the subdivision fingerprint run
(3,192 species with at least two photos). Top-1 74.4%, top-3 86.2%. That run's published boost
keeps 30,966 image prototypes and 1,082 name prototypes, pack version 2026111804, SHA-256
`e90577d7d723f490b5d1af9e1a0d641293fb4abe43cda072f0a4802e1e51cabb`, 26,656,768 bytes.
The model file is SHA-256 `40b2b79ee4416809562d9ee21423fa402f6a14c3b9d1ca00c4e8b83e3ef7a297`,
308,678,880 bytes. Nothing new was uploaded.

Z-scored fusion of BioCLIP 2 and DINOv2 S on the same 38,371 photos is 73.1% top-1 and 84.4% top-3.
It is worse than BioCLIP 2 alone, so it is not the recommended brain.

## Country pack version 2026111900 and its boost

The country pack `ID.owpack` on the packs release is version 2026111900, 36,621 species,
661,184,512 bytes, SHA-256 `837b38292b7813fe12ea1def56f5d6dd39227bc9a054bf285217e1a7beb3d907`.
The file on disk matches that hash. It has 45,306 field photos and no `refpage` table.

`ID.bioclip2.owboost` was built against pack version **2026111816**, not 2026111900.
SHA-256 `13f9ed3ffbde3f0ba51da96cd96dfb6737f1dc236e0ea71bc483203d4d6dab7c`, 58,576,896 bytes.
39,382 image prototypes (5,568 species) and 31,008 name prototypes. Text offset 0.183.
Name-only top-1 on that build's own sample was 36.9% (top-5 57.0%).

The phone refuses a boost whose `pack_version` is not the pack's version. 2026111816 does not
equal 2026111900, so this boost will not load for the new country pack. It needs a rebuild
against 2026111900. It was not rebuilt here, and nothing was uploaded.

Species ids 1 through 36,576 in the new pack all have a boost row, and every boost id exists in
the pack. The 45 ids with no row are 36,577–36,621, appended at the end (houseplants such as
Monstera adansonii, Zamioculcas zamiifolia and Chlorophytum comosum, and fungi such as
Termitomyces and Galerina marginata). That is the pattern of new species added after the boost,
not a renumbering of the old list. Those 45 species have 353 field photos and no fingerprint.

Another 32 species that already had ids below 36,577 now have field photos (59 photos) but only
a name prototype in the boost. Scindapsus pictus and Marasmius haematocephalus are two of them.
Any extra reference photos added to species that already had image prototypes are also absent.
The published boost cannot see the new references.

The 48.7% name-only figure in the table is from the subdivision boost build, not from this country
file. DINOv2 ViT-B/14 and the 700-species numbers are from the measured head-to-head on this same photo
store (700 species, three photos held out of five to eight). ViT-B is ahead of ViT-S and behind
BioCLIP 2. It is a different fingerprint length, so it needs new packs for every region. It was
not exported or staged.

## Held-out photos

28 Wikimedia Commons photos, not the pack's reference files. Plants include Asplenium nidus,
Monstera deliciosa and Dieffenbachia seguine. Fungi are common in the region: Schizophyllum
commune, Microporus xanthopus, Ganoderma lucidum, Pleurotus djamor, Auricularia cornea,
Cookeina tricholoma, Trametes coccinea, Lentinus sajor-caju. Scored against the libraries the
phone actually uses (essentials prototypes for DINOv2, the boost file for BioCLIP 2).

| Brain | Top-1 | Top-3 |
|---|---|---|
| DINOv2 S/14, one crop | 15/28 | 18/28 |
| DINOv2 S/14, several crops | 17/28 | 20/28 |
| BioCLIP 2, one crop | 22/28 | 25/28 |
| BioCLIP 2, several crops | 22/28 | 26/28 |

Several crops help a little. They do not close the gap. A Monstera fruit photo was rank 300 with
DINOv2 and rank 3 with BioCLIP 2. Two Dieffenbachia photos missed the top 3 for both (BioCLIP
ranks 6 and 14). One Commons file titled as Asplenium nidus was a Selaginella; DINOv2 called it
Selaginella, and that file was left out of the table.

## What was not shipped

- iNaturalist's computer-vision API is not public. Staff have described fee-based access for a
  few organisations. The public model-files repository is MIT and covers about 500 taxa, not this
  library. Not integrated.
- PlantCLEF 2024 ViT-B/14 (Zenodo 10.5281/zenodo.10848263) is CC BY 4.0, so it can be
  redistributed with credit. It is a closed-set classifier for 7,806 vascular plants of
  southwestern Europe, not a fingerprint model, and it does not cover fungi. It was not wired in.
- Kindwise Mushroom.id is a paid commercial API and its terms are a business contract. It also
  returns psychoactive fields. Not integrated. No fungi service had terms that allow this app to
  call it. Pl@ntNet identifies plants, not mushrooms.

## Recommendation

BioCLIP 2 is the Recommended badge. It is the most accurate brain on the leave-one-out and on
the held-out photos, for plants and for fungi. Fusing it with DINOv2 did not help. Standard stays
what runs until a matching boost is on the phone. The country pack 2026111900 does not accept the
published `ID.bioclip2.owboost`, so Indonesia on that pack keeps Standard until the boost is
rebuilt. Change `BrainList.RECOMMENDED` to move the badge. Fast and Careful stay as Accuracy and
speed: they are the same model, one look or several.

## Photo credits

Wikimedia Commons. These files were not used as reference photos. Plants For A Future and tropical.theferns.info were not used.

| Species | Photographer | Licence | File |
|---|---|---|---|
| Asplenium nidus | Salicyna | CC BY-SA 4.0 | Asplenium nidus 2016-05-09 9805.jpg |
| Asplenium nidus | Marija Gajić | CC BY-SA 4.0 | Asplenium nidus (11).JPG |
| Monstera deliciosa | H. Zell | CC BY 3.0 | Monstera deliciosa 001.jpg |
| Monstera deliciosa | Mokkie | CC BY-SA 3.0 | Window Plant (Monstera deliciosa) 1.jpg |
| Dieffenbachia seguine | Hanson59 | CC BY-SA 3.0 | Dieffenbachia seguine.jpg |
| Dieffenbachia seguine | Consultaplantas | CC BY-SA 4.0 | Dieffenbachia seguine 1.JPG |
| Epipremnum aureum | Satirdan kahraman | CC BY-SA 4.0 | Agora AVM'de şeytan sarmaşığı.jpg |
| Epipremnum aureum | Dinkun Chen | CC BY-SA 4.0 | Epipremnum aureum in China.jpg |
| Caladium bicolor | Ji-Elle | CC BY-SA 4.0 | Caladium bicolor-Jardin des plantes de Nantes (2).jpg |
| Caladium bicolor | Forest and Kim Starr | CC BY 3.0 | Starr 080103-1364 Caladium bicolor.jpg |
| Dracaena trifasciata | Joyifeoma45 | CC BY-SA 4.0 | A Dracaena trifasciata.jpg |
| Dracaena trifasciata | Greg III Espera | CC BY 4.0 | Dracaena trifasciata 120738827.jpg |
| Schizophyllum commune | Syrio | CC BY-SA 4.0 | Schizophyllum commune 02.jpg |
| Schizophyllum commune | Holger Krisp | CC BY 3.0 | Spaltblättling Schizophyllum commune.jpg |
| Microporus xanthopus | Vengolis | CC BY-SA 4.0 | Microporus xanthopus 7364.jpg |
| Microporus xanthopus | Ibenk Kusyanto | CC BY-SA 4.0 | Microporus xanthopus, Pananjung, West Java |
| Ganoderma lucidum | Eric Steinert | CC BY-SA 3.0 | Ganoderma lucidum 01.jpg |
| Ganoderma lucidum | Gorbunova I.A. | CC BY 4.0 | Ganoderma lucidum (Altai, Russia).png |
| Pleurotus djamor | Susanne Sourell, Mushroom Observer | CC BY-SA 3.0 | Pleurotus djamor var. roseus 400691.jpg |
| Pleurotus djamor | Telma Padilla, Mushroom Observer | CC BY-SA 3.0 | Pleurotus djamor var. roseus 506548.jpg |
| Auricularia cornea | Heddy Julistiono, Mushroom Observer | CC BY-SA 3.0 | Auricularia cornea 521199.jpg |
| Auricularia cornea | J. A. Cooper | CC BY 3.0 | Auricularia Cornea, Wood ear.jpg |
| Cookeina tricholoma | Britt Bunyard, Mushroom Observer | CC BY-SA 3.0 | Cookeina tricholoma 449633.jpg |
| Cookeina tricholoma | Gerald, Mushroom Observer | CC BY-SA 3.0 | Cookeina tricholoma 467929.jpg |
| Trametes coccinea | Jack Bain | CC BY 4.0 | Pycnoporus coccineus, Bunya Mountains |
| Trametes coccinea | Jessivigia | CC0 | Oligoporus y Pycnoporus coccineusn.jpg |
| Lentinus sajor-caju | John McCormack, Mushroom Observer | CC BY-SA 3.0 | Lentinus sajor-caju 266517.jpg |
| Lentinus sajor-caju | Renaudsechet | CC BY-SA 4.0 | Het nang fah.jpg |
