"""Turn many reference fingerprints per species into a few compact int8 prototypes.

Several prototypes per species capture real variation (flower vs leaf, young vs old, cap vs gills).
Each prototype is an L2-normalised cluster centre, quantised to int8 with a per-vector scale.
"""
from __future__ import annotations

import numpy as np

from . import config


def l2n(x: np.ndarray, axis: int = -1) -> np.ndarray:
    return x / np.maximum(np.linalg.norm(x, axis=axis, keepdims=True), 1e-12)


def n_prototypes(n_photos: int, max_k: int = config.MAX_PROTOTYPES) -> int:
    """Few photos: keep each one (nearest-photo matching handles odd angles best).
    More photos: cluster to ~1 prototype per 4 photos, at least 4, at most max_k."""
    if n_photos <= 4:
        return max(1, n_photos)
    return int(min(max_k, max(4, n_photos // 4)))


def spherical_kmeans(x: np.ndarray, k: int, iters: int = 25, seed: int = 0) -> np.ndarray:
    """Cosine k-means on unit vectors with k-means++ init. Returns (k, D) unit centres."""
    x = l2n(x.astype(np.float32))
    n = len(x)
    if k >= n:
        return x.copy()
    rng = np.random.default_rng(seed)
    centres = [x[rng.integers(n)]]
    for _ in range(1, k):
        d = 1.0 - np.max(x @ np.stack(centres).T, axis=1)
        d = np.maximum(d, 0) ** 2
        p = d / d.sum() if d.sum() > 0 else np.full(n, 1 / n)
        centres.append(x[rng.choice(n, p=p)])
    c = np.stack(centres)
    for _ in range(iters):
        assign = np.argmax(x @ c.T, axis=1)
        new = np.stack([x[assign == j].sum(0) if np.any(assign == j) else c[j] for j in range(k)])
        new = l2n(new)
        if np.allclose(new, c, atol=1e-6):
            break
        c = new
    return c


def quantize(v: np.ndarray) -> tuple[bytes, float]:
    """float vector -> (int8 bytes, scale) with value ≈ int8 * scale."""
    v = np.asarray(v, dtype=np.float32)
    scale = float(np.max(np.abs(v)) / 127.0) or 1.0
    q = np.clip(np.round(v / scale), -127, 127).astype(np.int8)
    return q.tobytes(), scale


def dequantize(blob: bytes, scale: float) -> np.ndarray:
    return np.frombuffer(blob, dtype=np.int8).astype(np.float32) * scale


def build_species_prototypes(embs: np.ndarray, seed: int = 0, keep_each: bool = False) -> list[tuple[bytes, float]]:
    """One prototype per reference photo when keep_each is set (life stages stay distinct).

    Clustering is for large unlabeled sets. A seedling and a flower of the same species
    should not be averaged together, or a camera photo of one stage misses the other.
    """
    vecs = l2n(np.asarray(embs, dtype=np.float32))
    if keep_each:
        if len(vecs) > config.MAX_PROTOTYPES:
            vecs = vecs[: config.MAX_PROTOTYPES]
        return [quantize(v) for v in vecs]
    k = n_prototypes(len(vecs))
    return [quantize(c) for c in spherical_kmeans(vecs, k, seed=seed)]
