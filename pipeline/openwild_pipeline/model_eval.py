"""Head-to-head photo-ID test of candidate recognition models on a region's own photos.

Same test for every model: species with at least 5 usable photos; for each, 3 photos are held out as the
"user's photos" and the rest form the library. We report how often the right plant comes first (and in the
top 3) from 1, 2 and 3 user photos, plain and weighted by how common the plant is.
"""
from __future__ import annotations

import csv
import json
import random
import time
from pathlib import Path

import numpy as np

from . import config


def sample(region: str, n_species: int, per_species: int = 8, seed: int = 0) -> list[dict]:
    from .commercial_photos import looks_like_sheet

    rows = list(csv.DictReader(open(config.WORK / "photos_commercial" / region / "photos.csv", encoding="utf-8")))
    by: dict[str, list[dict]] = {}
    for r in rows:
        p = Path(r.get("path") or "")
        if r.get("stage") in ("specimen", "related") or not p.exists():
            continue
        by.setdefault(r["scientific_name"].lower(), []).append(r)
    rnd = random.Random(seed)
    names = sorted(k for k, v in by.items() if len(v) >= 5)
    rnd.shuffle(names)
    out = []
    for k in names:
        v = [r for r in by[k] if not looks_like_sheet(Path(r["path"]), r.get("page"), r.get("url"))]
        if len(v) >= 5:
            out += v[:per_species]
        if len({r["scientific_name"].lower() for r in out}) >= n_species:
            break
    return out


def embed_dinov2_small(paths: list[Path]) -> np.ndarray:
    from .freemodel import embed_tflite

    return embed_tflite(paths, config.ANDROID_ASSETS / "embedder.tflite")


def _torch_embed(paths: list[Path], model, preprocess, fn, batch: int = 32) -> np.ndarray:
    import torch
    from PIL import Image

    out = []
    with torch.no_grad():
        for i in range(0, len(paths), batch):
            ims = []
            for p in paths[i:i + batch]:
                try:
                    ims.append(preprocess(Image.open(p).convert("RGB")))
                except Exception:  # noqa: BLE001
                    ims.append(torch.zeros(3, 224, 224))
            e = fn(model, torch.stack(ims)).float()
            out.append(torch.nn.functional.normalize(e, dim=-1).numpy())
            if i % (batch * 20) == 0:
                print(f"  {i}/{len(paths)}", flush=True)
    return np.concatenate(out)


def embed_bioclip2(paths: list[Path]) -> np.ndarray:
    import open_clip
    import torch

    torch.set_num_threads(4)
    model, _, preprocess = open_clip.create_model_and_transforms("hf-hub:imageomics/bioclip-2")
    return _torch_embed(paths, model.eval(), preprocess, lambda m, x: m.encode_image(x))


def embed_timm(name: str):
    def f(paths: list[Path]) -> np.ndarray:
        import timm
        import torch

        torch.set_num_threads(4)
        model = timm.create_model(name, pretrained=True, num_classes=0).eval()
        cfg = timm.data.resolve_data_config({}, model=model)
        return _torch_embed(paths, model, timm.data.create_transform(**cfg), lambda m, x: m(x))
    return f


MODELS = {
    "dinov2-small (current app)": embed_dinov2_small,
    "dinov2-base": embed_timm("vit_base_patch14_dinov2.lvd142m"),
    "bioclip-2": embed_bioclip2,
}


def score(embs: np.ndarray, labels: list[str], obs: dict[str, float], seed: int = 0) -> dict:
    rng = np.random.default_rng(seed)
    y = np.array(labels)
    groups: dict[str, list[int]] = {}
    for i, s in enumerate(labels):
        groups.setdefault(s, []).append(i)
    held = {s: rng.permutation(v)[:3] for s, v in groups.items() if len(v) >= 5}
    mask = np.ones(len(y), bool)
    for v in held.values():
        mask[v] = False
    G, gy = embs[mask], y[mask]
    sp = sorted(set(gy))
    idx = {s: i for i, s in enumerate(sp)}
    gi = np.array([idx[s] for s in gy])
    order = np.argsort(gi)
    bounds = np.r_[0, np.flatnonzero(np.diff(gi[order])) + 1]
    res = {f"{k}": [0, 0] for k in (1, 2, 3)}
    wres = {"1": 0.0, "3": 0.0}
    wsum = 0.0
    for s, ids in held.items():
        if s not in idx:
            continue
        b = np.maximum.reduceat((embs[ids] @ G.T)[:, order], bounds, axis=1) / 0.03
        b = b - b.max(1, keepdims=True)
        b = b - np.log(np.exp(b).sum(1, keepdims=True))
        w = float(np.sqrt(obs.get(s, 0) + 1))
        wsum += w
        for k in (1, 2, 3):
            ranked = np.argsort(-b[:k].sum(0))
            res[str(k)][0] += ranked[0] == idx[s]
            res[str(k)][1] += idx[s] in ranked[:3]
            if str(k) in wres:
                wres[str(k)] += w * (ranked[0] == idx[s])
    n = len(held)
    return {
        "species": n,
        **{f"top1_{k}photo": round(v[0] / n, 3) for k, v in res.items()},
        **{f"top3_{k}photo": round(v[1] / n, 3) for k, v in res.items()},
        **{f"common_weighted_top1_{k}photo": round(v / wsum, 3) for k, v in wres.items()},
    }


def run(region: str, n_species: int, out: Path, models: list[str] | None = None) -> dict:
    rows = sample(region, n_species)
    paths = [Path(r["path"]) for r in rows]
    labels = [r["scientific_name"].lower() for r in rows]
    sp_csv = config.DATA_REGIONS / region / "species.csv"
    obs = {r["scientific_name"].lower(): float(r.get("gbif_count") or 0) for r in csv.DictReader(open(sp_csv, encoding="utf-8"))}
    print(f"EVAL {len(paths)} photos, {len(set(labels))} species", flush=True)
    report = {}
    for name, fn in MODELS.items():
        if models and not any(m in name for m in models):
            continue
        t = time.time()
        try:
            e = fn(paths).astype(np.float32)
        except Exception as ex:  # noqa: BLE001
            report[name] = {"error": repr(ex)}
            print(f"EVAL {name}: failed {ex!r}", flush=True)
            continue
        r = score(e, labels, obs)
        r["seconds_per_photo_cpu"] = round((time.time() - t) / max(len(paths), 1), 3)
        report[name] = r
        print(f"EVAL {name}: {json.dumps(r)}", flush=True)
        out.write_text(json.dumps(report, indent=1))
    return report
