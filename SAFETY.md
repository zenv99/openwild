# Safety Rules

These rules are enforced in code (`android/app/src/main/java/org/openwild/app/safety/SafetyPolicy.kt`) and in the content pipeline (`pipeline/openwild_pipeline/content.py`). Changing them requires a pull request reviewed by at least one maintainer **and** one domain expert (botanist, mycologist, or toxicologist).

## 1. Harms before benefits
Every species page shows **Harms** first, then **Benefits**, then **Feeling**. Deadly lookalikes appear above everything else.

## 2. Confidence gating
| Tier | What the user sees |
|---|---|
| **High** | Full species page including the complete "If eaten" section. |
| **Medium** | Name, description, harms and lookalikes. Benefits and "Feeling" hidden behind: *"Not confident enough to show eating information. Do not eat."* |
| **Low** | Candidate list only, with *"Not confident. Do not eat."* |
| **Not in library** | *"This doesn't match anything in your region pack. Treat as unsafe."* |

## 3. Deadly-candidate warning
If **any** candidate in the top 5 with fused probability ≥ 2% is rated *deadly*, a red banner is shown on the results screen regardless of the top result: *"Could be [species] — DEADLY. Do not eat."*

## 4. No safety data = unsafe
A species with no reviewed content shows: *"No safety information yet. Treat as unsafe. Do not eat."* Unknown is never displayed as edible.

## 5. Mushrooms
- A permanent note on every fungus result: *"Mushrooms are a poor survival food: very few calories and high risk. Prioritise plants."*
- Edibility is never shown above Medium-tier gating for fungi unless at least 2 photos were taken and they include the underside (gills/pores) — the app prompts for it.

## 6. Psychoactive species
Identified like any other species. The page shows:
- The standard legal warning: *"Psychoactive. Contains substances controlled in many countries. Possession or use may be illegal where you are. Toxic and deadly lookalikes exist."*
- General, encyclopedia-level reported effects and their risks (e.g. anxiety, panic, nausea, impaired judgement — dangerous in a survival situation).
- **Never**: dosages, amounts, preparation or extraction methods, or ways to consume. The content validator rejects entries containing these.

## 7. Benefits are reported, not promised
Traditional or medicinal uses are labelled *"Reported/traditional use"* and must cite a source. No medical claims.

## 8. Symptom timing is mandatory for toxic species
Every *serious* or *deadly* entry must state onset timing and what to do. Example: amatoxin mushrooms often show no symptoms for 6–24 hours, then a false recovery, then liver failure — the user must seek medical care immediately, even if they feel fine.

## 9. Permanent footer
Every species page and result screen: **"Never eat anything based only on this app."**

## 10. Review status is visible
Every content entry shows *Expert reviewed* or *Not yet expert reviewed*. The seed content in `content/curated/` is **not yet expert reviewed** and must be reviewed before a public release. Priority: the ~200 deadliest species worldwide.
