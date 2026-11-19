"""Photo inbox: photos people send from the app (after agreeing on a permission screen), checked by a bot.

The app writes each submission to the private repo ``openwild-inbox``:

    inbox/<REGION>/<species_slug>/<id>/1_flower.jpg, 2_leaves.jpg, ..., submission.json

``submission.json`` is written last: {species, region, credit, license, own_photos, photos:[{file, stage}]}.

For every complete submission the bot:
  1. checks the basics: known region and species, CC BY 4.0, a name to credit, readable photos of a useful size;
  2. re-encodes each photo (drops any metadata that survived) and throws out sheets/drawings on paper;
  3. fingerprints each photo with the phone's own model and compares it with the region's library;
  4. decides:
       - ACCEPT straight away when the plant has no photo of its own yet and the photos look like a plant
         (that is the gap we most want filled), or when the plant has photos and ours agree it is that plant;
       - REVIEW (a person decides) when the photos don't match the named plant well, or look odd;
       - REJECT when nothing usable is left (not a photo, too small, a sheet, not a plant at all).
Accepted photos go to content/community/<REGION>/ credited "Name (OpenWild community)" and lead that plant's
gallery after the next pack build. Rejected submissions are deleted from the inbox.
"""

from __future__ import annotations

import csv
import hashlib
import json
import re
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from . import config
from .community import COMMUNITY, FIELDS, STAGES, clean_credit, licence_ok, reencode, slug

MIN_SIDE = 320          # shorter side in pixels, after re-encoding
PLANT_MIN = 0.40        # best similarity to any plant photo in the library (real plant photos: 99% score >= 0.45)
TOPK_AGREE = 10         # the named plant should be among this many best matches
DUPLICATE = 0.97        # near-identical to a photo already in the library: a copy, not the sender's own photo


@dataclass
class Decision:
    verdict: str                      # accept | review | reject
    reason: str
    photos: list[dict] = field(default_factory=list)   # usable photos: {bytes, stage, file}
    detail: dict = field(default_factory=dict)


def load_library(pack: Path) -> tuple[np.ndarray, np.ndarray, dict[str, int], set[int], dict[int, str]]:
    """Prototype vectors (L2-normalised), their species ids, name->id, ids with their own photos, id->genus."""
    from .prototypes import dequantize

    con = sqlite3.connect(pack)
    rows = con.execute("SELECT species_id, vec, scale FROM prototype WHERE kind = 'image'").fetchall()
    ids = np.array([r[0] for r in rows], dtype=np.int64)
    vecs = np.stack([dequantize(r[1], r[2]) for r in rows]) if rows else np.zeros((0, config.EMBED_DIM), np.float32)
    vecs /= np.linalg.norm(vecs, axis=1, keepdims=True) + 1e-8
    names = {n.lower(): i for i, n in con.execute("SELECT id, scientific_name FROM species")}
    genus = {i: (n.split()[0].lower() if n else "") for i, n in con.execute("SELECT id, scientific_name FROM species")}
    have = {r[0] for r in con.execute("SELECT DISTINCT species_id FROM photo WHERE stage NOT IN ('related', 'specimen', 'exact_specimen', 'example')")}
    con.close()
    return vecs, ids, names, have, genus


def decide(meta: dict, images: list[tuple[str, str, bytes]], library, embed, is_sheet) -> Decision:
    """``images``: (file, stage, raw bytes). ``embed(list[bytes]) -> np.ndarray`` (L2-normalised rows).
    ``is_sheet(bytes) -> bool``. Pure apart from those two callbacks, so it is unit-tested without the model."""
    vecs, ids, names, have, genus = library
    name = (meta.get("species") or "").strip()
    sid = names.get(name.lower())
    if sid is None:
        return Decision("reject", f"'{name}' is not a plant in this region's library")
    if not licence_ok(meta.get("license", "")):
        return Decision("reject", "photos must be shared under CC BY 4.0")
    credit = clean_credit(meta.get("credit", ""))
    if not credit:
        return Decision("reject", "no name to credit")
    if meta.get("own_photos") is not True:
        return Decision("reject", "the sender did not confirm these are their own photos")

    usable = []
    dropped = {"unreadable": 0, "too_small": 0, "sheet": 0}
    for file, stage, raw in images:
        jpg = reencode(raw)
        if jpg is None:
            dropped["unreadable"] += 1
            continue
        from PIL import Image
        import io
        w, h = Image.open(io.BytesIO(jpg)).size
        if min(w, h) < MIN_SIDE:
            dropped["too_small"] += 1
            continue
        if is_sheet(jpg):
            dropped["sheet"] += 1
            continue
        usable.append({"file": file, "stage": stage if stage in STAGES else "field", "bytes": jpg})
    if not usable:
        return Decision("reject", "no usable photos (" + ", ".join(f"{v} {k}" for k, v in dropped.items() if v) + ")")

    q = embed([u["bytes"] for u in usable])
    sims = q @ vecs.T if len(vecs) else np.zeros((len(q), 0))
    best = sims.max(axis=1) if sims.size else np.zeros(len(q))
    detail = {"best_similarity": [round(float(b), 3) for b in best], "dropped": dropped, "had_photos": sid in have}

    plant_like = [i for i, b in enumerate(best) if b >= PLANT_MIN]
    if not plant_like:
        return Decision("reject", "these don't look like plant photos", detail=detail)
    if len(plant_like) < len(usable):
        detail["not_plant_like"] = len(usable) - len(plant_like)
        usable = [usable[i] for i in plant_like]
        sims, best = sims[plant_like], best[plant_like]

    # Per photo: rank of the named plant among species (by each species' best prototype).
    agree, dup_other = 0, 0
    same_genus = 0
    for row in sims:
        order = np.argsort(-row)
        seen: list[int] = []
        for j in order:
            s = int(ids[j])
            if s not in seen:
                seen.append(s)
            if len(seen) >= TOPK_AGREE:
                break
        if sid in seen:
            agree += 1
        if any(genus.get(s) == genus.get(sid) for s in seen):
            same_genus += 1
        if row[order[0]] >= DUPLICATE:
            dup_other += 1
    detail.update({"agree": agree, "same_genus": same_genus, "copies_of_library_photos": dup_other, "photos": len(usable)})

    if dup_other:
        return Decision("review", "a photo looks like a copy of one already in the library", usable, detail)
    if sid not in have:
        # The gap we most want filled: no photo of this plant yet. Accept plant photos.
        return Decision("accept", "first photos of this plant", usable, detail)
    if agree * 2 >= len(usable):
        return Decision("accept", "photos match this plant", usable, detail)
    return Decision("review", "the photos don't clearly match this plant; a person will check", usable, detail)


def add_to_library(region: str, meta: dict, sub_id: str, photos: list[dict], page: str = "") -> list[dict]:
    """Write accepted photos under content/community/<region>/ and append them to community.csv."""
    name = meta["species"].strip()
    folder = COMMUNITY / region / slug(name)
    folder.mkdir(parents=True, exist_ok=True)
    csv_path = COMMUNITY / region / "community.csv"
    known = set()
    if csv_path.exists():
        known = {r.get("url", "") for r in csv.DictReader(open(csv_path, encoding="utf-8"))}
    rows = []
    for k, p in enumerate(photos):
        digest = hashlib.sha256(p["bytes"]).hexdigest()[:16]
        key = f"inbox:{digest}"
        if key in known:
            continue
        dest = folder / f"inbox_{sub_id}_{k + 1}_{p['stage']}.jpg"
        dest.write_bytes(p["bytes"])
        rows.append({
            "scientific_name": name, "stage": p["stage"],
            "observer": f"{clean_credit(meta['credit'])} (OpenWild community)", "license": "cc-by",
            "page": page, "url": key, "path": str(dest.relative_to(config.REPO_ROOT)),
            "source": "openwild-community", "issue": "",
        })
    if rows:
        new = not csv_path.exists()
        with open(csv_path, "a", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=FIELDS)
            if new:
                w.writeheader()
            w.writerows(rows)
    return rows


def submissions(inbox: Path) -> list[Path]:
    """Complete submissions (folders with submission.json), oldest first."""
    return sorted((p.parent for p in inbox.glob("inbox/*/*/*/submission.json")), key=lambda p: p.stat().st_mtime)


_SAFE = re.compile(r"^[A-Za-z0-9_.-]+$")


def read_submission(folder: Path) -> tuple[dict, list[tuple[str, str, bytes]]]:
    meta = json.loads((folder / "submission.json").read_text(encoding="utf-8"))
    images = []
    for ph in meta.get("photos", [])[:20]:
        f = str(ph.get("file", ""))
        if not _SAFE.match(f) or not (folder / f).is_file():
            continue
        if (folder / f).stat().st_size > 15_000_000:
            continue
        images.append((f, str(ph.get("stage", "field")), (folder / f).read_bytes()))
    return meta, images


# ---------------------------------------------------------------- feedback (votes, reviews), weekly

FEEDBACK_FIELDS = ["date", "kind", "species", "rating", "text", "correct", "photo", "stage", "app_version", "source"]
HIDE_MIN_NO = 3          # a photo needs at least this many "wrong plant" answers...
HIDE_RATIO = 2.0         # ...and twice as many no as yes, before it is hidden


def collect_feedback(inbox: Path, today: str) -> tuple[list[Path], dict[str, list[dict]]]:
    """Read feedback/<REGION>/<species>/*.json. Returns (files read, rows by region). Text is length-limited
    and kept as plain text; nothing in it is ever executed or turned into app content without a person."""
    files, by_region = [], {}
    for f in sorted(inbox.glob("feedback/*/*/*.json")):
        files.append(f)
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        region = f.parts[-3]
        kind = str(d.get("kind", ""))[:12]
        if kind not in ("vote", "review") or d.get("region") != region:
            continue
        by_region.setdefault(region, []).append({
            "date": today, "kind": kind, "species": str(d.get("species", ""))[:120],
            "rating": int(d.get("rating") or 0) if str(d.get("rating", "0")).lstrip("-").isdigit() else 0,
            "text": re.sub(r"\s+", " ", str(d.get("text", "")))[:4000],
            "correct": "" if "correct" not in d else ("yes" if d.get("correct") is True else "no"),
            "photo": str(d.get("photo", ""))[:500], "stage": str(d.get("stage", ""))[:20],
            "app_version": str(d.get("app_version", ""))[:20],
            "source": str(f.relative_to(inbox))[:300],
        })
    return files, by_region


def save_feedback(region: str, rows: list[dict]) -> Path:
    path = COMMUNITY / region / "feedback.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    seen: set[tuple] = set()
    if path.exists():
        for prev in csv.DictReader(open(path, encoding="utf-8")):
            seen.add(_feedback_key(prev))
    fresh = []
    for row in rows:
        key = _feedback_key(row)
        if key in seen:
            continue
        seen.add(key)
        fresh.append(row)
    new = not path.exists()
    with open(path, "a", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=FEEDBACK_FIELDS)
        if new:
            w.writeheader()
        w.writerows(fresh)
    return path


def _feedback_key(row: dict) -> tuple:
    """Identity of a vote or review, ignoring the day it was filed, so a weekly re-read does not double-count."""
    return (
        row.get("kind", ""), row.get("species", ""), row.get("photo", ""), row.get("correct", ""),
        (row.get("text") or "")[:80], str(row.get("rating") or ""), row.get("app_version", ""),
        row.get("source", ""),
    )


def hidden_photos(region: str) -> set[str]:
    """Photos left out of packs: a maintainer hide list, plus photos people say show the wrong plant
    (enough "no", clearly more than "yes")."""
    hidden: set[str] = set()
    listed = COMMUNITY / region / "hide.txt"
    if listed.exists():
        for line in listed.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                hidden.add(line)
    path = COMMUNITY / region / "feedback.csv"
    if not path.exists():
        return hidden
    votes: dict[str, list[int]] = {}
    for r in csv.DictReader(open(path, encoding="utf-8")):
        if r.get("kind") == "vote" and r.get("photo"):
            v = votes.setdefault(r["photo"], [0, 0])
            v[0 if r.get("correct") == "yes" else 1] += 1
    hidden |= {u for u, (yes, no) in votes.items() if no >= HIDE_MIN_NO and no >= HIDE_RATIO * yes}
    return hidden


def digest(by_region: dict[str, list[dict]]) -> str:
    lines = []
    for region, rows in sorted(by_region.items()):
        reviews = [r for r in rows if r["kind"] == "review" and (r["text"] or r["rating"])]
        votes = [r for r in rows if r["kind"] == "vote"]
        no = sum(1 for r in votes if r["correct"] == "no")
        lines.append(f"## {region}\n\n{len(reviews)} reviews, {len(votes)} photo answers ({no} said wrong plant).\n")
        for r in reviews[:200]:
            stars = "★" * max(0, min(5, r["rating"]))
            txt = r["text"].replace("@", "@​")[:600]   # no accidental GitHub mentions
            lines.append(f"- *{r['species']}* {stars} {txt}")
        lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------- reports (wrong ID / corrections), weekly

REPORT_FIELDS = ["date", "kind", "predicted", "target", "matched", "verdict", "photos", "top_matches", "pack_version"]
CONFUSION_MIN = 3


def reports(inbox: Path) -> list[Path]:
    """Wrong-ID folders, including ones already moved to review/."""
    found = list(inbox.glob("reports/*/*/*/report.json"))
    found += [p for p in inbox.glob("review/**/report.json") if p.is_file()]
    return sorted({p.parent for p in found}, key=lambda p: str(p))


def resolve_name(text: str, names: dict[str, int], aka: dict[str, str]) -> str | None:
    """The plant a reporter named: scientific name, or any common / local name in the pack (lowercase)."""
    t = (text or "").strip().lower()
    if not t:
        return None
    if t in names:
        return t
    return aka.get(t)


def load_aka(pack: Path) -> dict[str, str]:
    """Every common and local name (lowercase) -> scientific name (lowercase), unique names only."""
    con = sqlite3.connect(pack)
    seen: dict[str, set[str]] = {}
    for sci, common, cn in con.execute("SELECT scientific_name, common_name, common_names FROM species"):
        alln = [common or ""]
        try:
            alln += [p for v in (json.loads(cn) if cn else {}).values() for p in re.split(r"[;,/]", str(v))]
        except Exception:  # noqa: BLE001
            pass
        for n in alln:
            n = n.strip().lower()
            if len(n) >= 3:
                seen.setdefault(n, set()).add(sci.lower())
    con.close()
    return {n: next(iter(s)) for n, s in seen.items() if len(s) == 1}


# Suggested names from a wrong identification. status is "review" until a person marks "reviewed".
# A phone cannot set status; candidate_from_report ignores anything the report itself claims.
CANDIDATE_FIELDS = [
    "date", "region", "suggested", "matched", "predicted", "note", "top_matches", "model",
    "pack_version", "app_version", "has_photos", "has_embedding", "share_location", "status", "source",
]


def candidate_from_report(rep: dict, region: str, matched: str | None, source: str, today: str, status: str = "review") -> dict:
    """A candidate label for a person to check. Never trusted, even if the payload says so."""
    suggested = str(rep.get("suggested") or rep.get("guess") or rep.get("chosen") or "").strip()[:160]
    kept = "reviewed" if status == "reviewed" else "review"
    return {
        "date": today,
        "region": region[:32],
        "suggested": suggested,
        "matched": (matched or "")[:160],
        "predicted": str(rep.get("predicted") or "")[:160],
        "note": re.sub(r"\s+", " ", str(rep.get("note") or "")).strip()[:500],
        "top_matches": str(rep.get("top_matches") or "")[:800],
        "model": str(rep.get("model") or "")[:80],
        "pack_version": str(rep.get("pack_version") or "")[:40],
        "app_version": str(rep.get("app_version") or "")[:40],
        "has_photos": "yes" if rep.get("photos") else "no",
        "has_embedding": "yes" if rep.get("embeddings") else "no",
        "share_location": "yes" if rep.get("share_location") is True else "no",
        "status": kept,
        "source": source[:300],
    }


def _candidate_key(row: dict) -> tuple:
    return (
        row.get("source", ""), row.get("suggested", ""), row.get("predicted", ""),
        row.get("status", ""), (row.get("note") or "")[:80],
    )


def save_candidates(region: str, rows: list[dict]) -> int:
    """Append candidate labels. A weekly re-read does not duplicate them. Returns how many were new."""
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", region or ""):
        return 0
    path = COMMUNITY / region / "candidate_labels.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    seen: set[tuple] = set()
    if path.exists():
        for prev in csv.DictReader(open(path, encoding="utf-8")):
            seen.add(_candidate_key(prev))
    fresh = []
    for row in rows:
        item = {k: row.get(k, "") for k in CANDIDATE_FIELDS}
        item["status"] = "reviewed" if item.get("status") == "reviewed" else "review"
        key = _candidate_key(item)
        if key in seen:
            continue
        seen.add(key)
        fresh.append(item)
    new = not path.exists()
    with open(path, "a", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=CANDIDATE_FIELDS)
        if new:
            w.writeheader()
        w.writerows(fresh)
    return len(fresh)


def fingerprint_jobs(region: str) -> list[dict]:
    """Reviewed labels that name a species already resolved to the library.

    Unreviewed rows are not jobs. A free-text suggestion with no matched species is not a job:
    a fingerprint is only queued once a person has confirmed which plant it is.
    """
    path = COMMUNITY / region / "candidate_labels.csv"
    if not path.exists():
        return []
    out = []
    for row in csv.DictReader(open(path, encoding="utf-8")):
        if row.get("status") == "reviewed" and (row.get("matched") or "").strip():
            out.append(row)
    return out


def wrong_id_feedback(inbox: Path, today: str) -> list[tuple[str, dict]]:
    """Wrong-ID notes that landed in feedback/ instead of reports/. (region, candidate row)."""
    rows = []
    for f in sorted(inbox.glob("feedback/*/*/*.json")):
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        if str(d.get("kind", "")) not in ("wrong", "correction", "report"):
            continue
        region = f.parts[-3]
        if d.get("region") not in (None, "", region):
            continue
        rows.append((region, candidate_from_report(d, region, None, str(f.relative_to(inbox)), today)))
    return rows


def save_reports(region: str, rows: list[dict]) -> None:
    path = COMMUNITY / region / "reports.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    new = not path.exists()
    with open(path, "a", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=REPORT_FIELDS)
        if new:
            w.writeheader()
        w.writerows(rows)


def confusions(region: str) -> list[tuple[str, str, int]]:
    """(predicted, actual, count) pairs reported at least CONFUSION_MIN times."""
    path = COMMUNITY / region / "reports.csv"
    if not path.exists():
        return []
    c: dict[tuple[str, str], int] = {}
    for r in csv.DictReader(open(path, encoding="utf-8")):
        a, b = (r.get("predicted") or "").lower(), (r.get("matched") or "").lower()
        if a and b and a != b:
            c[(a, b)] = c.get((a, b), 0) + 1
    return [(a, b, n) for (a, b), n in c.items() if n >= CONFUSION_MIN]
