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


GROUP_RULES = config.REPO_ROOT / "content" / "group_rules.yaml"


def validate_rule(rule: dict) -> list[str]:
    errs = []
    for f in ("taxon", "rank", "kingdom", "toxicity", "warning", "onset", "what_to_do", "source"):
        if not rule.get(f):
            errs.append(f"missing {f}")
    if rule.get("rank") not in ("genus", "family"):
        errs.append("rank must be genus or family")
    if rule.get("toxicity") not in ("mild", "serious", "deadly"):
        errs.append("rule toxicity must be mild, serious or deadly (rules only warn)")
    for k in ("warning", "onset", "what_to_do"):
        t = rule.get(k) or ""
        if DOSE_WORDS.search(t) or AMOUNT.search(t) or (rule.get("psychoactive") and PREP_WORDS.search(t)):
            errs.append(f"{k}: contains dosage/consumption language")
    return errs


def load_group_rules(path: Path = GROUP_RULES, strict: bool = True) -> dict[tuple[str, str], dict]:
    """{(rank, taxon_lower): rule}. Raises on invalid rules."""
    rules: dict[tuple[str, str], dict] = {}
    problems = []
    if not Path(path).exists():
        return rules
    for r in yaml.safe_load(Path(path).read_text()) or []:
        for e in validate_rule(r):
            problems.append(f"{r.get('taxon')}: {e}")
        key = (r.get("rank"), (r.get("taxon") or "").lower())
        if key in rules:
            problems.append(f"duplicate rule {key}")
        rules[key] = r
    if problems and strict:
        raise ContentError("\n".join(problems))
    return rules


def group_content(rule: dict) -> dict:
    """Pack content for a species covered only by a genus/family rule. Never edible."""
    out = {
        "edibility": "unknown",
        "toxicity": rule["toxicity"],
        "psychoactive": bool(rule.get("psychoactive")),
        "level": rule["rank"],
        "group_taxon": rule["taxon"],
        "if_eaten": {"harms": {"symptoms": rule["warning"], "onset": rule["onset"], "what_to_do": rule["what_to_do"]}},
        "survival": {"priority": "avoid" if rule["toxicity"] in ("serious", "deadly") else "neutral"},
        "sources": [rule["source"]],
        "review": {"status": "unreviewed"},
    }
    if out["psychoactive"]:
        out["legal_warning"] = LEGAL_WARNING
    return out


QUICK_DIR = config.REPO_ROOT / "content" / "quick"
VERDICTS = {"edible", "edible_cooked", "inedible", "medicinal", "toxic", "deadly", "psychoactive"}


def validate_quick(row: dict) -> list[str]:
    errs = []
    if not (row.get("scientific_name") or "").strip():
        return ["missing scientific_name"]
    v = (row.get("verdict") or "").strip()
    if v not in VERDICTS:
        errs.append(f"bad verdict {v!r} (use one of {sorted(VERDICTS)})")
    if v in ("toxic", "deadly", "psychoactive") and not (row.get("harm") or "").strip():
        errs.append(f"verdict {v} needs a harm line")
    for u in filter(None, (x.strip() for x in (row.get("survival") or "").split(";"))):
        if u not in SURVIVAL_USES:
            errs.append(f"bad survival use {u!r}")
    for f in ("benefit", "harm"):
        t = row.get(f) or ""
        if DOSE_WORDS.search(t) or AMOUNT.search(t):
            errs.append(f"{f}: contains dosage/amount language")
        if v == "psychoactive" and PREP_WORDS.search(t):
            errs.append(f"{f}: psychoactive entry contains preparation/consumption language")
    return errs


def load_quick(directory: Path = QUICK_DIR, strict: bool = True) -> dict[str, dict]:
    """Bulk one-liner entries from content/quick/*.csv -> {scientific_name_lower: row}."""
    import csv

    rows: dict[str, dict] = {}
    problems = []
    # auto_* files (generated from open datasets) load first; hand-written files override them.
    files = sorted(Path(directory).glob("*.csv"), key=lambda p: (not p.name.startswith("auto_"), p.name))
    for f in files:
        lines = [ln for ln in f.read_text(encoding="utf-8").splitlines() if ln.strip() and not ln.lstrip().startswith("#")]
        for r in csv.DictReader(lines):
            r = {k.strip(): (v or "").strip() for k, v in r.items() if k}
            for e in validate_quick(r):
                problems.append(f"{f.name}: {r.get('scientific_name')}: {e}")
            key = r.get("scientific_name", "").lower()
            if key in rows and not rows[key].get("_auto"):
                problems.append(f"{f.name}: duplicate {r.get('scientific_name')}")
            if f.name.startswith("auto_"):
                r["_auto"] = "1"
            rows[key] = r
    if problems and strict:
        raise ContentError("\n".join(problems))
    return rows


def quick_content(row: dict) -> dict:
    """Pack content for a one-liner entry. Shown with 'not expert reviewed'; eating info still needs High confidence."""
    v = row["verdict"]
    benefit, harm = row.get("benefit") or None, row.get("harm") or None
    source = row.get("source") or f"Wikipedia: {row['scientific_name']}"
    uses = [u.strip() for u in (row.get("survival") or "").split(";") if u.strip()]
    out: dict = {
        "level": "quick",
        "psychoactive": v == "psychoactive",
        "if_eaten": {},
        "survival": {"priority": "neutral", "uses": uses},
        "sources": [source],
        "review": {"status": "unreviewed"},
    }
    ie = out["if_eaten"]
    if v == "edible":
        out.update(edibility="edible", toxicity="mild" if harm else "none")
        ie["benefits"] = {"nutrition": benefit, "edible_parts": []}
        if harm:
            ie["harms"] = {"symptoms": harm}
        out["survival"]["priority"] = "high_value" if "food" in uses or not uses else "neutral"
    elif v == "edible_cooked":
        out.update(edibility="edible_with_preparation", toxicity="mild")
        ie["benefits"] = {"nutrition": benefit, "preparation_required": "Cook thoroughly before eating."}
        ie["harms"] = {"symptoms": harm or "Can upset the stomach or irritate the mouth if eaten raw.", "cooking_makes_safe": True}
        out["survival"]["priority"] = "use_with_caution"
    elif v in ("inedible", "medicinal"):
        out.update(edibility="inedible", toxicity="none" if not harm else "mild")
        if harm:
            ie["harms"] = {"symptoms": harm}
        if benefit:
            ie["benefits"] = {"traditional_uses": [{"text": benefit, "source": source}]}
    elif v == "toxic":
        out.update(edibility="toxic", toxicity="serious")
        ie["harms"] = {"symptoms": harm, "onset": "Varies by species and amount.", "what_to_do": "Seek medical care."}
        out["survival"]["priority"] = "avoid"
    elif v == "deadly":
        out.update(edibility="deadly", toxicity="deadly")
        ie["harms"] = {"symptoms": harm, "onset": "Can be fast or delayed by many hours; seek care even without symptoms.",
                       "what_to_do": "Seek emergency medical care immediately."}
        out["survival"]["priority"] = "avoid"
    elif v == "psychoactive":
        out.update(edibility="psychoactive", toxicity="mild", legal_warning=LEGAL_WARNING)
        ie["harms"] = {"symptoms": harm, "onset": "Usually within an hour.", "what_to_do": "Seek medical care for severe agitation, chest pain or seizures."}
        out["survival"]["priority"] = "avoid"
    return out


def resolve(curated: dict[str, dict], rules: dict, scientific_name: str, genus: str | None, family: str | None,
            quick: dict[str, dict] | None = None) -> dict | None:
    """Species entry > quick one-liner > genus rule > family rule > None."""
    entry = curated.get(scientific_name.lower())
    if entry is not None:
        return finalize(entry)
    if quick and scientific_name.lower() in quick:
        return quick_content(quick[scientific_name.lower()])
    g = genus or scientific_name.split(" ")[0]
    for key in (("genus", (g or "").lower()), ("family", (family or "").lower())):
        if key in rules:
            return group_content(rules[key])
    return None


def finalize(entry: dict | None) -> dict:
    """Content as stored in a pack: fill defaults, add the legal warning for psychoactive species."""
    if entry is None:
        return {"edibility": "unknown", "toxicity": "unknown", "review": {"status": "unreviewed"}}
    out = dict(entry)
    out.setdefault("review", {"status": "unreviewed"})
    out.setdefault("level", "species")
    if out.get("psychoactive"):
        out["legal_warning"] = LEGAL_WARNING
    return out


def wikipedia_intros(titles: list[str], lang: str = "en") -> dict[str, dict]:
    """Batched first-sentence intros for up to 50 titles per request (MediaWiki API). {title: {description, url}}."""
    from .http import get_json

    out: dict[str, dict] = {}
    for i in range(0, len(titles), 20):
        batch = titles[i : i + 20]
        try:
            d = get_json(
                f"https://{lang}.wikipedia.org/w/api.php",
                {"action": "query", "format": "json", "prop": "extracts|info", "exintro": 1, "explaintext": 1,
                 "exsentences": 1, "exlimit": 20, "redirects": 1, "inprop": "url", "titles": "|".join(batch)},
            )
        except Exception:
            continue
        q = d.get("query", {})
        back = {r["to"]: r["from"] for r in q.get("redirects", [])}
        back.update({n["to"]: n["from"] for n in q.get("normalized", [])})
        for page in q.get("pages", {}).values():
            text = (page.get("extract") or "").strip()
            if not text or "may refer to" in text:
                continue
            title = page.get("title")
            original = back.get(title, title)
            original = back.get(original, original)
            out[original] = {"description": text[:300], "url": page.get("fullurl", "")}
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
