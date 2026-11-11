# Species Content Schema (version 1)

Stored as JSON in the pack `content` table and authored as YAML in `content/curated/*.yaml`. Validated by `pipeline/openwild_pipeline/content.py`.

```yaml
scientific_name: Amanita phalloides      # required, matches species list
edibility: deadly                         # edible | edible_with_preparation | inedible | toxic | deadly | psychoactive | unknown
toxicity: deadly                          # none | mild | serious | deadly | unknown
psychoactive: false
if_eaten:
  harms:                                  # required when toxicity is mild/serious/deadly
    toxic_parts: [whole mushroom]
    cooking_makes_safe: false
    symptoms: "Severe vomiting, diarrhoea and abdominal pain, then apparent recovery, then liver and kidney failure."
    onset: "Usually 6–24 hours after eating. Often a false recovery on day 2–3."   # required for serious/deadly
    severity: "Frequently fatal without urgent treatment."
    what_to_do: "Seek emergency medical care immediately, even with no symptoms. Tell doctors you may have eaten a death cap."
  benefits:
    nutrition: null
    edible_parts: []
    preparation_required: null
    traditional_uses: []                  # [{text: ..., source: ...}] — reported use, never a medical claim
  feeling:
    taste_texture: null
    effects: null                         # general, encyclopedic; no doses or preparation
    risks: null
survival:
  priority: avoid                         # high_value | use_with_caution | avoid | neutral
  uses: []                                # water | fire | shelter | cordage | medicinal | food
lookalikes:
  - name: Amanita citrina
    danger: harmless
sources:
  - "Wikipedia: Amanita phalloides"
review:
  status: unreviewed                      # unreviewed | expert_reviewed
  reviewer: null
  date: null
```

## Validation rules (enforced)
1. `toxicity` in {serious, deadly} ⇒ `harms.symptoms`, `harms.onset`, `harms.what_to_do` are non-empty.
2. `edibility: edible` ⇒ `toxicity` in {none, mild}. Species that are only safe after preparation (e.g. cassava) use `edible_with_preparation` and keep their real raw toxicity.
3. `edibility: edible_with_preparation` ⇒ `benefits.preparation_required` non-empty.
3a. `toxicity: mild` ⇒ `harms.symptoms` non-empty.
4. `psychoactive: true` ⇒ the app adds the standard legal warning; `feeling.effects` may describe general effects only.
5. No text field may contain dosage or consumption instructions (amounts with units such as g/mg/ml next to consumption words, or words like *dose*, *dosage*, *microdose*, *extract*, *brew*, *how to take*). Rejected entries fail the build.
6. `traditional_uses` entries require a `source`.
