# Houseplant check, ID-BA

The leave-one-out on the region's own reference photos is unchanged, because this pass does not
replace the fingerprint model or the pack. That figure is in ACCURACY.md: 52.4% top-1, 64.6% top-3
on 38,433 photos. An earlier published run on 25,929 photos was 49.7% top-1 and 61.4% top-3.

What changed is how a phone photo is scored against those same fingerprints.

## Asplenium nidus

The species is in the ID-BA pack (6 image prototypes, 2 display photos, 22 regional records).
It is not removed by the region filter. A whole-plant photo that fills the frame already ranks first
(similarity about 0.80). A phone-style frame of that same photo, with wall and floor around the plant,
drops to rank 4 with one centre crop. Several crops bring it to rank 2. The houseplant prior floor
then brings it to rank 1.

A wavy cultivated form (`Fimbriatum`) was rank 3 with one crop and rank 1 after several crops plus
the floor. A close-up of spore cases still misses; that is not a habit photo.

Lookalike fern display photos (Nephrolepis cordifolia, Microsorum musifolium, Microsorum scolopendria,
Asplenium tenerum, Pyrrosia piloselloides) stayed rank 1 for their own species after the floor.

## Held-out houseplant photos

Eleven openly licensed Wikimedia Commons photos (whole plants, not the pack's own display file),
scored against the ID-BA essentials prototypes with the built-in model:

| Scoring | Top-1 | Top-3 |
|---|---|---|
| One centre crop | 5/11 | 7/11 |
| Several crops | 6/11 | 8/11 |
| Several crops and the houseplant floor | 8/11 | 8/11 |

Monstera deliciosa and Dieffenbachia seguine in this set still miss. The floor does not invent a match
when the fingerprint is far away.

## In-library display photos

200 random display photos from the same pack, which are close to the prototypes they helped build
(an optimistic check, not the leave-one-out):

| Scoring | Top-1 | Top-3 |
|---|---|---|
| One centre crop | 81.0% | 82.0% |
| Several crops | 81.0% | 82.0% |
| Houseplant floor | 81.0% | 82.0% |
| Both | 81.0% | 82.0% |

## Speed and size

Built-in model `ow-dinov2s-384-v1`: DINOv2 ViT-S/14, Apache-2.0, 22.8 MB on disk.
On this 4-core Xeon with XNNPACK: about 43 ms for one crop and 135 ms for four crops.
A mid-range phone CPU was not available here. Embedding is the slow part; prototype search of a
pack this size stays well under that. Careful mode is a short wait, not a new download.

BioCLIP 2 (`imageomics/bioclip-2`, MIT) is published as an optional download, about 309 MB, plus a
region boost. A boost is published for ID-BA only, so it is not the default: other regions would
lose photo identification. DINOv2 ViT-B/14 is Apache-2.0 and was not shipped. It is a different
fingerprint length and would need new packs. A Pl@ntNet classifier was not shipped: it is not a
drop-in fingerprint model for these packs, and this pass did not confirm a redistribution licence
that fits the app.
