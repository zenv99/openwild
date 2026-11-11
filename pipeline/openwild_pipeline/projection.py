"""Optional PCA projection that shrinks fingerprints (768 -> 256 dims by default).

Lets a region pack hold tens of thousands of species while staying small: prototypes are stored
in the projected space and the app projects each photo fingerprint with the same matrix.
"""
from __future__ import annotations

import numpy as np

from .prototypes import l2n


def fit(embs: np.ndarray, k: int = 256, max_rows: int = 200_000, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """Return (W, mean): W is (k, D) with orthonormal rows, mean is (D,)."""
    x = l2n(embs.astype(np.float32))
    if len(x) > max_rows:
        x = x[np.random.default_rng(seed).choice(len(x), max_rows, replace=False)]
    mean = x.mean(0)
    xc = x - mean
    cov = (xc.T @ xc) / max(1, len(xc) - 1)
    vals, vecs = np.linalg.eigh(cov)
    order = np.argsort(vals)[::-1][: min(k, x.shape[1])]
    return vecs[:, order].T.astype(np.float32), mean.astype(np.float32)


def apply(x: np.ndarray, w: np.ndarray, mean: np.ndarray) -> np.ndarray:
    """Project and re-normalise. Works for (D,) or (N, D). Mirrors android core/Projection.kt."""
    single = x.ndim == 1
    z = l2n((np.atleast_2d(x).astype(np.float32) - mean) @ w.T)
    return z[0] if single else z


def explained_variance(embs: np.ndarray, w: np.ndarray, mean: np.ndarray) -> float:
    x = l2n(embs.astype(np.float32)) - mean
    total = float((x ** 2).sum())
    kept = float(((x @ w.T) ** 2).sum())
    return kept / total if total else 0.0
