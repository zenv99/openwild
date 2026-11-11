"""Distil BioCLIP 2 (teacher) into the small mobile student.

Runs on a single free GPU (Kaggle T4/P100 or Colab T4). Teacher embeddings are computed once per
photo (with light augmentation done on the student side only), cached, then the student is trained
to match them with a cosine loss. Photos: union of all regions' selections.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

from . import config


def _list_photos(photo_parquets: list[Path]) -> list[Path]:
    import duckdb

    from .photos import photo_path

    paths: list[Path] = []
    seen: set[int] = set()
    for pq in photo_parquets:
        for pid, ext in duckdb.sql(f"SELECT photo_id, extension FROM '{pq}'").fetchall():
            if pid in seen:
                continue
            seen.add(pid)
            p = photo_path(int(pid), ext)
            if p.exists():
                paths.append(p)
    return paths


def teacher_embed(photo_parquets: list[Path], out: Path, batch: int = 64, device: str = "cuda") -> Path:
    import torch
    from PIL import Image
    from tqdm import tqdm

    from .model import load_teacher

    paths = _list_photos(photo_parquets)
    model, preprocess, _ = load_teacher(device)
    embs = np.zeros((len(paths), config.EMBED_DIM), dtype=np.float16)
    with torch.no_grad(), torch.autocast(device_type="cuda", enabled=device == "cuda"):
        for i in tqdm(range(0, len(paths), batch), desc="teacher"):
            imgs = torch.stack([preprocess(Image.open(p).convert("RGB")) for p in paths[i : i + batch]]).to(device)
            z = torch.nn.functional.normalize(model.encode_image(imgs).float(), dim=-1)
            embs[i : i + len(imgs)] = z.cpu().numpy().astype(np.float16)
    out.parent.mkdir(parents=True, exist_ok=True)
    np.savez(out, embs=embs, paths=np.array([str(p) for p in paths]))
    return out


def train_student(
    teacher_npz: Path,
    out: Path,
    epochs: int = 12,
    batch: int = 128,
    lr: float = 1e-3,
    device: str = "cuda",
    workers: int = 4,
) -> Path:
    import torch
    from PIL import Image
    from torch.utils.data import DataLoader, Dataset
    from tqdm import tqdm

    from .model import build_student, student_transform

    data = np.load(teacher_npz)
    targets = torch.from_numpy(data["embs"].astype(np.float32))
    paths = list(data["paths"])
    tf = student_transform(train=True)

    class DS(Dataset):
        def __len__(self):
            return len(paths)

        def __getitem__(self, i):
            return tf(Image.open(paths[i]).convert("RGB")), targets[i]

    dl = DataLoader(DS(), batch_size=batch, shuffle=True, num_workers=workers, drop_last=True, pin_memory=True)
    student = build_student(pretrained=True).to(device)
    opt = torch.optim.AdamW(student.parameters(), lr=lr, weight_decay=0.05)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=lr, total_steps=epochs * len(dl), pct_start=0.1)
    scaler = torch.amp.GradScaler(enabled=device == "cuda")

    for epoch in range(epochs):
        student.train()
        running = 0.0
        for x, y in tqdm(dl, desc=f"epoch {epoch + 1}/{epochs}"):
            x, y = x.to(device, non_blocking=True), y.to(device, non_blocking=True)
            with torch.autocast(device_type="cuda", enabled=device == "cuda"):
                z = student(x)
                loss = (1 - (z.float() * y).sum(-1)).mean()   # cosine distillation loss
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            running += loss.item()
        print(f"epoch {epoch + 1}: mean cosine loss {running / len(dl):.4f}")
        out.parent.mkdir(parents=True, exist_ok=True)
        torch.save({"model_id": config.MODEL_ID, "state_dict": student.state_dict()}, out)
    return out


def load_student(ckpt: Path, device: str = "cpu"):
    import torch

    from .model import build_student

    blob = torch.load(ckpt, map_location=device)
    if blob.get("model_id") != config.MODEL_ID:
        raise ValueError(f"Checkpoint model_id {blob.get('model_id')} != {config.MODEL_ID}")
    s = build_student(pretrained=False)
    s.load_state_dict(blob["state_dict"])
    return s.eval().to(device)
