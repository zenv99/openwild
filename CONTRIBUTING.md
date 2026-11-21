# Contributing to OpenWild

OpenWild is a free gift to anyone who finds themselves outdoors and needs to know what's growing around them. Every contribution helps.

The in-app list (**About → Volunteer & improve OpenWild**) works offline. A link opens in the browser only when someone taps it. Nothing is sent in the background.

## Easiest way to help

You can help without opening GitHub.

- After an identification, tap **Wrong ID?** if the name is wrong. The report stays on the phone until you send it.
- On the match list, tap **This is it** when you know the plant. That saves a correction you can send.
- On a species page, save a **review** with local names, tips, or corrections. Sharing it is optional.

## Ways to help

### Add or fix plant photos

Close-ups of leaves, flowers, fruit, and the whole plant help the most. On a species page, **Add your photos** saves pictures on the phone (GPS, time, and camera details removed) and opens the plant-photos form only when you continue.

#### Photo rules

Use only openly licensed photos:

- **Wikimedia Commons**
- **iNaturalist** photos under a Creative Commons licence (CC0, CC BY, CC BY-SA, CC BY-NC, or CC BY-NC-SA)
- **GBIF** media only when the licence is clearly stated and is one of those same open licences

Always credit the photographer, the licence, and a link to the source. No-derivatives (ND) licences are not accepted.

Never use photos from **Plants For A Future** (pfaf.org) or **tropical.theferns.info**.

### Check plant safety info

Botanists, mycologists, toxicologists, and experienced foragers: review entries in `content/curated/`, correct them, and set `review: {status: expert_reviewed, reviewer: "<name, credential>", date: YYYY-MM-DD}`. Priority is the deadliest species.

**Safety data needs a source.** Every claim about edibility, toxicity, lookalikes, symptom timing, or what to do must cite one. Follow `docs/CONTENT_SCHEMA.md` and run `python -m openwild_pipeline.cli content-validate` before opening a pull request.

Changes to `SAFETY.md`, `core/SafetyPolicy.kt`, or the content validator need review from a maintainer and a domain expert. No dosages, preparation, or consumption instructions for psychoactive species. **Never eat anything based only on this app.**

### Translate the app

Copy `android/app/src/main/res/values/strings.xml` to `values-<lang>/strings.xml`. Safety strings must be translated exactly — ask a native speaker to check them.

### Report a bug or idea

Open a new GitHub issue: https://github.com/zenv99/openwild/issues/new

In the app, **About → Send feedback** opens the same kind of issue, or an email if a feedback address is configured. Nothing is sent until you press send.

### Help with code

The repository is https://github.com/zenv99/openwild

- Kotlin app in `android/`. Python pipeline in `pipeline/`. Keep `core/` Android-free so it stays unit-testable.
- Add or improve regions in `regions/regions.yaml`, then run `regions-export`.
- Herbaria, societies, and projects: see `DATA_SOURCES.md` and add an entry to `regions/partners.yaml`.

Confidence logic changes must update both `pipeline/openwild_pipeline/confidence.py` and `android/.../core/Confidence.kt`, and keep the shared fixture test passing.

## Rules

- Changes to `SAFETY.md`, `core/SafetyPolicy.kt` or the content validator need review from a maintainer **and** a domain expert.
- No ads, analytics, tracking, paid features or accounts. The app must work fully offline after a pack download.
- No dosages, preparation or consumption instructions for psychoactive species.
- Confidence logic changes must update both `pipeline/openwild_pipeline/confidence.py` and `android/.../core/Confidence.kt`, and keep the shared fixture test passing.

## Code license

By contributing you agree your code is licensed under Apache-2.0 and your content contributions under CC BY-SA 4.0.
