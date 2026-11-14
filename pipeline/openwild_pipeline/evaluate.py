"""How well does photo ID pick the right plant? Leave-one-out on the region's reference photos.

Each reference photo is treated as if a user had just taken it: we hide that photo's own fingerprint,
rank every species by its closest remaining fingerprint (as the phone does), and record where the true
species lands. Only species with at least two photos can be tested this way.

This measures the matching itself on good, labelled photos. Real phone photos are messier, so treat it as
an upper bound; the in-app "Not this one?" corrections are what tell us about real use.
"""
from __future__ import annotations

import json
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np


def leave_one_out(labels: list[str], embs: np.ndarray, ks=(1, 3, 5, 10)) -> dict:
    labels = [l.strip() for l in labels]
    species = sorted(set(labels))
    idx = {s: i for i, s in enumerate(species)}
    y = np.array([idx[l] for l in labels])
    e = embs.astype(np.float32)
    e = e / (np.linalg.norm(e, axis=1, keepdims=True) + 1e-9)
    counts = Counter(y.tolist())
    testable = np.array([counts[c] >= 2 for c in y])
    ranks = []
    confusions: Counter = Counter()
    per_species: dict[str, list[int]] = defaultdict(list)
    for q in np.nonzero(testable)[0]:
        sims = e @ e[q]
        sims[q] = -np.inf                      # hide this photo's own fingerprint
        best = np.full(len(species), -np.inf, np.float32)
        np.maximum.at(best, y, sims)           # each species scored by its closest fingerprint
        order = np.argsort(-best)
        rank = int(np.nonzero(order == y[q])[0][0]) + 1
        ranks.append(rank)
        per_species[labels[q]].append(rank)
        if rank > 1:
            confusions[(labels[q], species[order[0]])] += 1
    ranks = np.array(ranks)
    n = len(ranks)
    report = {
        "photos_tested": int(n),
        "species_tested": int(len(per_species)),
        "species_total": int(len(species)),
        "top_k": {f"top{k}": round(float((ranks <= k).mean()), 4) if n else None for k in ks},
        "median_rank": float(np.median(ranks)) if n else None,
        "most_confused": [{"true": a, "shown_first": b, "times": c} for (a, b), c in confusions.most_common(40)],
        "hardest_species": sorted(
            ({"species": s, "photos": len(r), "top1": round(float(np.mean(np.array(r) == 1)), 3)} for s, r in per_species.items()
             if len(r) >= 3),
            key=lambda d: (d["top1"], -d["photos"]))[:40],
    }
    return report


def write_report(report: dict, folder: Path, region_id: str) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "accuracy.json").write_text(json.dumps(report, indent=1))
    t = report["top_k"]
    pct = lambda v: "n/a" if v is None else f"{v * 100:.1f}%"
    lines = [
        f"# Photo-ID accuracy: {region_id}",
        "",
        "Leave-one-out on the pack's own reference photos: each photo is matched as if a user had just taken it,",
        "with its own fingerprint hidden. Good labelled photos, so treat this as an upper bound for real use.",
        "",
        f"- Photos tested: {report['photos_tested']} across {report['species_tested']} species "
        f"(of {report['species_total']} with photos; a species needs 2+ photos to be tested)",
        f"- Right plant shown first: {pct(t.get('top1'))}",
        f"- In the top 3: {pct(t.get('top3'))} · top 5: {pct(t.get('top5'))} · top 10: {pct(t.get('top10'))}",
        f"- Median position of the right plant: {report['median_rank']}",
        "",
        "## Most often confused (true plant -> shown first instead)",
        "",
    ]
    lines += [f"- {c['true']} -> {c['shown_first']} ({c['times']}x)" for c in report["most_confused"][:25]]
    lines += ["", "## Hardest species (3+ test photos, lowest top-1)", ""]
    lines += [f"- {h['species']}: {h['top1'] * 100:.0f}% top-1 over {h['photos']} photos" for h in report["hardest_species"][:25]]
    (folder / "ACCURACY.md").write_text("\n".join(lines) + "\n")
    print(f"ACCURACY {region_id}: top1 {pct(t.get('top1'))}, top3 {pct(t.get('top3'))}, top10 {pct(t.get('top10'))} "
          f"over {report['photos_tested']} photos / {report['species_tested']} species", flush=True)
