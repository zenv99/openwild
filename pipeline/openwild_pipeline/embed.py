"""Compute student fingerprints for a region's reference photos, plus text fallback prototypes."""
from __future__ import annotations

from pathlib import Path

import numpy as np

from . import config


def embed_region_photos(ckpt: Path, photos_parquet: Path, out: Path, batch: int = 64, device: str = "cuda") -> Path:
    """Saves npz with: embs (N, D) float16, taxon_ids (N,), photo_ids (N,)."""
    import duckdb
    import torch
    from PIL import Image
    from tqdm import tqdm

    from .distill import load_student
    from .model import student_transform
    from .photos import photo_path

    rows = duckdb.sql(f"SELECT photo_id, extension, taxon_id FROM '{photos_parquet}'").fetchall()
    rows = [(int(p), e, int(t)) for p, e, t in rows if photo_path(int(p), e).exists()]
    model = load_student(ckpt, device)
    tf = student_transform(train=False)
    embs = np.zeros((len(rows), config.EMBED_DIM), dtype=np.float16)
    with torch.no_grad():
        for i in tqdm(range(0, len(rows), batch), desc="student"):
            chunk = rows[i : i + batch]
            x = torch.stack([tf(Image.open(photo_path(p, e)).convert("RGB")) for p, e, _ in chunk]).to(device)
            embs[i : i + len(chunk)] = model(x).cpu().numpy().astype(np.float16)
    out.parent.mkdir(parents=True, exist_ok=True)
    np.savez(
        out,
        embs=embs,
        taxon_ids=np.array([t for _, _, t in rows], dtype=np.int64),
        photo_ids=np.array([p for p, _, _ in rows], dtype=np.int64),
    )
    return out


def text_prototypes(names: list[str], device: str = "cuda") -> np.ndarray:
    """Teacher text embeddings for species with too few photos (flagged 'text', lower trust in the app)."""
    import torch

    from .model import load_teacher

    model, _, tokenizer = load_teacher(device)
    out = []
    with torch.no_grad():
        for i in range(0, len(names), 256):
            prompts = [f"a photo of {n}." for n in names[i : i + 256]]
            z = model.encode_text(tokenizer(prompts).to(device)).float()
            out.append(torch.nn.functional.normalize(z, dim=-1).cpu().numpy())
    return np.concatenate(out) if out else np.zeros((0, config.EMBED_DIM), np.float32)
