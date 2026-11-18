"""BioCLIP 2 on the phone: the optional "Better recognition" model.

Same contract as the built-in model, so the app only swaps the file:
  input  float32 NHWC [1, 224, 224, 3], RGB in [0, 1], centre-cropped square (the app's own preprocessing)
  output float32 [1, 768], L2-normalised, BioCLIP 2 image-embedding space
CLIP normalisation happens inside the exported graph.

Region "boost" files hold BioCLIP 2 fingerprints for the same reference photos as the region pack, keyed by
the pack's own species ids, so the app can match with either model.
"""
from __future__ import annotations

import csv
import json
import sqlite3
from pathlib import Path

import numpy as np

from . import config

HUB = "hf-hub:imageomics/bioclip-2"
MODEL_ID = "bioclip2-vitl14-224-v1"
DIM = 768
MEAN = (0.48145466, 0.4578275, 0.40821073)
STD = (0.26862954, 0.26130258, 0.27577711)


def build():
    import open_clip
    import torch
    from torch import nn

    clip, _, _ = open_clip.create_model_and_transforms(HUB)
    visual = clip.visual.eval()

    class Embedder(nn.Module):
        def __init__(self):
            super().__init__()
            self.v = visual
            self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1))
            self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

        def forward(self, x_nhwc):
            x = (x_nhwc.permute(0, 3, 1, 2) - self.mean) / self.std
            return torch.nn.functional.normalize(self.v(x), dim=-1)

    return Embedder().eval()


def export(out_dir: Path) -> Path:
    import litert_torch
    import torch

    from .freemodel import _parity

    model = build()
    sample = (torch.rand(1, config.INPUT_SIZE, config.INPUT_SIZE, 3),)
    out_dir.mkdir(parents=True, exist_ok=True)
    float_path = out_dir / "bioclip2_float.tflite"
    out = out_dir / "bioclip2.tflite"
    litert_torch.convert(model, sample).export(str(float_path))
    print(f"float model {float_path.stat().st_size / 1e6:.1f} MB", flush=True)
    try:
        from ai_edge_quantizer import quantizer, recipe

        qt = quantizer.Quantizer(str(float_path))
        qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
        qt.quantize().export_model(str(out), overwrite=True)
    except Exception as e:  # noqa: BLE001
        print(f"int8 quantisation failed ({e!r}); keeping float32", flush=True)
        out.write_bytes(float_path.read_bytes())
    float_path.unlink(missing_ok=True)
    (out_dir / "bioclip2.json").write_text(json.dumps({
        "model_id": MODEL_ID, "embedding_dim": DIM, "input_size": config.INPUT_SIZE, "bytes": out.stat().st_size,
        "source": "imageomics/bioclip-2 (MIT licence), image encoder only",
    }))
    print(out, f"{out.stat().st_size / 1e6:.1f} MB", flush=True)
    _parity(model, out)
    return out


def embed_paths(paths: list[Path], model: Path) -> np.ndarray:
    from .freemodel import embed_tflite

    return embed_tflite(paths, model)


def boost_pack(full_pack: Path, photos_csv: Path, vecs: dict[str, np.ndarray], out: Path) -> dict:
    """One BioCLIP 2 prototype per reference photo of the published pack (matched by the photo's page URL)."""
    from .prototypes import quantize

    by_page: dict[str, str] = {}
    for r in csv.DictReader(open(photos_csv, encoding="utf-8")):
        for k in (r.get("page"), r.get("url")):
            if k:
                by_page.setdefault(k, r["path"])
    src = sqlite3.connect(full_pack)
    meta = dict(src.execute("SELECT key, value FROM meta"))
    rows = src.execute("SELECT species_id, url FROM photo WHERE stage NOT IN ('related', 'specimen', 'exact_specimen')").fetchall()
    if src.execute("SELECT 1 FROM sqlite_master WHERE name='refpage'").fetchone():
        rows += src.execute("SELECT species_id, page FROM refpage").fetchall()   # extra reference photos
    seen_rows = set()
    rows = [r for r in rows if not (r in seen_rows or seen_rows.add(r))]
    src.close()
    out.unlink(missing_ok=True)
    con = sqlite3.connect(out)
    con.executescript("""
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE prototype (id INTEGER PRIMARY KEY, species_id INTEGER NOT NULL, vec BLOB NOT NULL,
                                scale REAL NOT NULL, kind TEXT NOT NULL DEFAULT 'image');
        CREATE INDEX prototype_species ON prototype(species_id);
    """)
    n, missing, species = 0, 0, set()
    for sid, url in rows:
        path = by_page.get(url)
        v = vecs.get(path) if path else None
        if v is None:
            missing += 1
            continue
        blob, scale = quantize(v / (np.linalg.norm(v) + 1e-8))
        con.execute("INSERT INTO prototype (species_id, vec, scale) VALUES (?,?,?)", (sid, blob, scale))
        n += 1
        species.add(sid)
    for k, v in {"region_id": meta["region_id"], "pack_version": meta["pack_version"], "model_id": MODEL_ID,
                 "embedding_dim": str(DIM), "kind": "boost"}.items():
        con.execute("INSERT INTO meta VALUES (?,?)", (k, v))
    con.commit()
    con.execute("VACUUM")
    con.close()
    return {"prototypes": n, "species": len(species), "missing": missing, "bytes": out.stat().st_size,
            "pack_version": meta["pack_version"]}


def text_prototypes(full_pack: Path, image_vecs: dict[str, np.ndarray], photos_csv: Path) -> tuple[dict[int, np.ndarray], float, dict]:
    """BioCLIP 2 knows species by name: embed "a photo of <taxonomy> <name>" for every plant, so plants with
    no reference photo can still be recognised. Returns ({species_id: vector}, offset, stats).

    Text-to-image similarities run lower than image-to-image ones; `offset` (fitted on plants that do have
    photos) lifts them onto the same scale, so a name match competes fairly with a photo match."""
    import open_clip
    import sqlite3
    import torch

    model, _, _ = open_clip.create_model_and_transforms(HUB)
    tok = open_clip.get_tokenizer(HUB)
    model.eval()
    con = sqlite3.connect(full_pack)
    rows = con.execute("SELECT id, scientific_name, common_name, kingdom, family, genus FROM species").fetchall()
    photo_rows = con.execute("SELECT species_id, url FROM photo WHERE stage NOT IN ('related', 'specimen', 'exact_specimen')").fetchall()
    con.close()
    texts = []
    for sid, sci, common, kingdom, family, genus in rows:
        tax = " ".join(x for x in (kingdom, family, genus) if x)
        t = f"a photo of {tax} {sci}" + (f" with common name {common}" if common else "") + "."
        texts.append((sid, t))
    out: dict[int, np.ndarray] = {}
    with torch.no_grad():
        for i in range(0, len(texts), 256):
            chunk = texts[i:i + 256]
            e = model.encode_text(tok([t for _, t in chunk])).float()
            e = torch.nn.functional.normalize(e, dim=-1).numpy()
            for (sid, _), v in zip(chunk, e):
                out[sid] = v.astype(np.float32)
    # Fit the offset on held-out photos of plants that have photos: median true image-image similarity
    # (to the plant's other photos) minus median true image-text similarity.
    by_page = {}
    for r in csv.DictReader(open(photos_csv, encoding="utf-8")):
        for k in (r.get("page"), r.get("url")):
            if k:
                by_page.setdefault(k, r["path"])
    per_sp: dict[int, list[np.ndarray]] = {}
    for sid, url in photo_rows:
        v = image_vecs.get(by_page.get(url, ""))
        if v is not None:
            per_sp.setdefault(sid, []).append(v / (np.linalg.norm(v) + 1e-8))
    ii, it = [], []
    ids = sorted(out)
    T = np.stack([out[s] for s in ids])
    zero_hit1 = zero_hit5 = zero_n = 0
    rng = np.random.default_rng(0)
    for sid, vs in per_sp.items():
        if len(vs) < 2:
            continue
        q, rest = vs[0], np.stack(vs[1:])
        ii.append(float((rest @ q).max()))
        it.append(float(out[sid] @ q))
        if rng.random() < 0.5:   # zero-shot check: rank by name only, among all plants
            order = np.argsort(-(T @ q))[:5]
            zero_n += 1
            zero_hit1 += ids[order[0]] == sid
            zero_hit5 += sid in [ids[j] for j in order]
    offset = float(np.median(ii) - np.median(it)) if ii else 0.0
    stats = {"text_protos": len(out), "offset": round(offset, 3), "image_sim_median": round(float(np.median(ii)), 3) if ii else None,
             "text_sim_median": round(float(np.median(it)), 3) if it else None,
             "zero_shot_top1": round(zero_hit1 / zero_n, 3) if zero_n else None,
             "zero_shot_top5": round(zero_hit5 / zero_n, 3) if zero_n else None}
    print("TEXT", json.dumps(stats), flush=True)
    return out, offset, stats


def add_text_to_boost(boost: Path, text: dict[int, np.ndarray], offset: float, only_missing: bool = True) -> int:
    """Name prototypes (kind 'text') for plants without image prototypes in the boost file."""
    import sqlite3

    from .prototypes import quantize

    con = sqlite3.connect(boost)
    have = {r[0] for r in con.execute("SELECT DISTINCT species_id FROM prototype WHERE kind = 'image'")}
    n = 0
    for sid, v in text.items():
        if only_missing and sid in have:
            continue
        blob, scale = quantize(v)
        con.execute("INSERT INTO prototype (species_id, vec, scale, kind) VALUES (?,?,?,'text')", (sid, blob, scale))
        n += 1
    con.execute("INSERT OR REPLACE INTO meta VALUES ('text_offset', ?)", (f"{offset:.4f}",))
    con.commit()
    con.execute("VACUUM")
    con.close()
    return n
