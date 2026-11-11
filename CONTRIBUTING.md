# Contributing to OpenWild

OpenWild is a free gift to anyone who finds themselves outdoors and needs to know what's growing around them. Every contribution helps.

## Ways to help

1. **Review safety content (most important).** Botanists, mycologists, toxicologists: review entries in `content/curated/`, correct them, and set `review: {status: expert_reviewed, reviewer: "<name, credential>", date: YYYY-MM-DD}`. Priority is the ~200 deadliest species worldwide.
2. **Add species content.** Follow `docs/CONTENT_SCHEMA.md`. Run `python -m openwild_pipeline.cli content-validate` before opening a PR. Every claim needs a source.
3. **Add or improve regions.** Edit `regions/regions.yaml`, then run `regions-export`.
4. **Share data.** Herbaria, societies and projects: see `DATA_SOURCES.md` and add an entry to `regions/partners.yaml`.
5. **Translate.** Copy `android/app/src/main/res/values/strings.xml` to `values-<lang>/strings.xml`. Safety strings must be translated exactly — ask a native speaker to check them.
6. **Code.** Kotlin app in `android/`, Python pipeline in `pipeline/`. Keep `core/` Android-free so it stays unit-testable.

## Rules

- Changes to `SAFETY.md`, `core/SafetyPolicy.kt` or the content validator need review from a maintainer **and** a domain expert.
- No ads, analytics, tracking, paid features or accounts. The app must work fully offline after a pack download.
- No dosages, preparation or consumption instructions for psychoactive species.
- Confidence logic changes must update both `pipeline/openwild_pipeline/confidence.py` and `android/.../core/Confidence.kt`, and keep the shared fixture test passing.

## Code license

By contributing you agree your code is licensed under Apache-2.0 and your content contributions under CC BY-SA 4.0.
