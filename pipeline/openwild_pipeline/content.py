"""Species safety content: load curated YAML, validate it (SAFETY.md rules), fetch Wikipedia summaries."""
from __future__ import annotations

import re
import urllib.parse
from pathlib import Path

import yaml

from . import config

EDIBILITY = {"edible", "edible_with_preparation", "inedible", "toxic", "deadly", "psychoactive", "unknown"}
TOXICITY = {"none", "mild", "serious", "deadly", "unknown"}
SURVIVAL_PRIORITY = {"high_value", "use_with_caution", "avoid", "neutral"}
SURVIVAL_USES = {"food", "water", "fire", "shelter", "cordage", "medicinal"}
DANGER = {"deadly", "toxic", "psychoactive", "harmless", "edible"}
REVIEW = {"unreviewed", "expert_reviewed"}

LEGAL_WARNING = (
    "Psychoactive. Contains substances controlled in many countries. Possession or use may be "
    "illegal where you are. Toxic and deadly lookalikes exist."
)

# Consumption-instruction patterns. Applied to every text field of psychoactive entries, and the
# dose words + amount patterns to every non-nutrition field of all entries.
DOSE_WORDS = re.compile(r"\b(dose|doses|dosage|dosing|microdos\w*|threshold dose|trip ?sitter)\b", re.I)
AMOUNT = re.compile(r"\b\d+(\.\d+)?\s?(mg|mcg|µg|ug|g|grams?|ml|millilit\w*|caps|pieces)\b", re.I)
PREP_WORDS = re.compile(r"\b(extract\w*|brew\w*|steep\w*|tea|smok(e|ing) it|how to (take|use|consume|prepare)|ingest\w* by)\b", re.I)


class ContentError(ValueError):
    pass


def _texts(obj, path=""):
    """Yield (path, string) for every string inside a nested dict/list."""
    if isinstance(obj, str):
        yield path, obj
    elif isinstance(obj, dict):
        for k, v in obj.items():
            yield from _texts(v, f"{path}.{k}" if path else k)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from _texts(v, f"{path}[{i}]")


def validate(entry: dict) -> list[str]:
    """Return a list of problems (empty = valid)."""
    errs: list[str] = []
    name = entry.get("scientific_name")
    if not name:
        return ["missing scientific_name"]
    ed, tox = entry.get("edibility", "unknown"), entry.get("toxicity", "unknown")
    if ed not in EDIBILITY:
        errs.append(f"bad edibility {ed!r}")
    if tox not in TOXICITY:
        errs.append(f"bad toxicity {tox!r}")
    ie = entry.get("if_eaten") or {}
    harms, benefits = ie.get("harms") or {}, ie.get("benefits") or {}

    if tox in ("serious", "deadly"):
        for f in ("symptoms", "onset", "what_to_do"):
            if not harms.get(f):
                errs.append(f"toxicity={tox} requires if_eaten.harms.{f}")
    if tox == "mild" and not harms.get("symptoms"):
        errs.append("toxicity=mild requires if_eaten.harms.symptoms")
    if ed == "edible" and tox not in ("none", "mild"):
        errs.append("edibility=edible requires toxicity none or mild (use edible_with_preparation)")
    if ed == "edible_with_preparation" and not benefits.get("preparation_required"):
        errs.append("edibility=edible_with_preparation requires benefits.preparation_required")
    if ed == "psychoactive" and not entry.get("psychoactive"):
        errs.append("edibility=psychoactive requires psychoactive: true")
    for i, use in enumerate(benefits.get("traditional_uses") or []):
        if not isinstance(use, dict) or not use.get("text") or not use.get("source"):
            errs.append(f"traditional_uses[{i}] needs text and source")

    surv = entry.get("survival") or {}
    if surv.get("priority", "neutral") not in SURVIVAL_PRIORITY:
        errs.append(f"bad survival.priority {surv.get('priority')!r}")
    for u in surv.get("uses") or []:
        if u not in SURVIVAL_USES:
            errs.append(f"bad survival use {u!r}")
    for i, lk in enumerate(entry.get("lookalikes") or []):
        if not lk.get("name") or lk.get("danger") not in DANGER:
            errs.append(f"lookalikes[{i}] needs name and danger in {sorted(DANGER)}")
    if (entry.get("review") or {}).get("status", "unreviewed") not in REVIEW:
        errs.append("bad review.status")
    if not entry.get("sources"):
        errs.append("at least one source required")

    psycho = bool(entry.get("psychoactive"))
    for path, text in _texts(entry):
        if path.startswith(("sources", "review", "scientific_name", "lookalikes")):
            continue
        if DOSE_WORDS.search(text):
            errs.append(f"{path}: contains dosage language")
        if "nutrition" not in path and AMOUNT.search(text):
            errs.append(f"{path}: contains an amount with units")
        if psycho and PREP_WORDS.search(text):
            errs.append(f"{path}: psychoactive entry contains preparation/consumption language")
    return errs


def load_curated(directory: Path = config.CURATED_DIR, strict: bool = True) -> dict[str, dict]:
    """Load all curated YAML files -> {scientific_name_lower: entry}. Raises on any invalid entry."""
    entries: dict[str, dict] = {}
    problems: list[str] = []
    for f in sorted(Path(directory).glob("*.yaml")):
        for entry in yaml.safe_load(f.read_text()) or []:
            key = (entry.get("scientific_name") or "").strip().lower()
            for e in validate(entry):
                problems.append(f"{f.name}: {entry.get('scientific_name')}: {e}")
            if key in entries:
                problems.append(f"{f.name}: duplicate entry {entry['scientific_name']}")
            entries[key] = entry
    if problems and strict:
        raise ContentError("\n".join(problems))
    return entries


def finalize(entry: dict | None) -> dict:
    """Content as stored in a pack: fill defaults, add the legal warning for psychoactive species."""
    if entry is None:
        return {"edibility": "unknown", "toxicity": "unknown", "review": {"status": "unreviewed"}}
    out = dict(entry)
    out.setdefault("review", {"status": "unreviewed"})
    if out.get("psychoactive"):
        out["legal_warning"] = LEGAL_WARNING
    return out


def wikipedia_summary(scientific_name: str, lang: str = "en") -> dict | None:
    """Short description + URL from Wikipedia's REST API (CC BY-SA). None if no page."""
    from .http import get_json

    title = urllib.parse.quote(scientific_name.replace(" ", "_"))
    try:
        d = get_json(f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{title}")
    except Exception:
        return None
    if d.get("type") == "disambiguation" or not d.get("extract"):
        return None
    return {"description": d["extract"], "url": (d.get("content_urls") or {}).get("desktop", {}).get("page")}
