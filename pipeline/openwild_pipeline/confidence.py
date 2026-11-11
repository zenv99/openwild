"""Reference implementation of OpenWild confidence scoring (docs/CONFIDENCE.md).

android/.../match/Confidence.kt must produce identical results; see tests/test_confidence.py.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

HIGH, MEDIUM, LOW, UNKNOWN = "HIGH", "MEDIUM", "LOW", "UNKNOWN"


@dataclass
class Params:
    temperature: float = 0.03
    photo_alpha: float = 0.6
    prior_beta: float = 0.5
    open_set_threshold: float = 0.30
    high_threshold: float = 0.85
    medium_threshold: float = 0.50
    text_proto_penalty: float = 0.05
    margin: float = 0.30


@dataclass
class Result:
    order: list[int]          # species indices, best first
    probs: np.ndarray         # fused probability per species
    tier: str
    score: int                # 0..100 for the top species
    agreement: float
    mean_top_sim: float


def log_softmax(z: np.ndarray, axis: int = -1) -> np.ndarray:
    m = np.max(z, axis=axis, keepdims=True)
    return z - m - np.log(np.sum(np.exp(z - m), axis=axis, keepdims=True))


def prior(obs_counts: np.ndarray, month_freq: np.ndarray | None) -> np.ndarray:
    """obs_counts (S,), month_freq (S,) in 0..255 for the current month, or None."""
    p = np.sqrt(obs_counts.astype(np.float64) + 1.0)
    if month_freq is not None:
        p = p * (month_freq.astype(np.float64) / 255.0 + 0.05)
    return p / p.sum()


def species_similarity(
    photo_embs: np.ndarray,          # (n, D) unit vectors
    protos: np.ndarray,              # (P, D) dequantised prototypes
    proto_species: np.ndarray,       # (P,) species index
    proto_is_text: np.ndarray,       # (P,) bool
    n_species: int,
    text_penalty: float,
) -> np.ndarray:
    """sim[p, s] = best prototype similarity for photo p and species s (−inf if no prototype)."""
    raw = photo_embs @ protos.T                                   # (n, P)
    raw = raw - text_penalty * proto_is_text[None, :].astype(np.float64)
    sim = np.full((len(photo_embs), n_species), -np.inf)
    for j in range(protos.shape[0]):
        s = proto_species[j]
        sim[:, s] = np.maximum(sim[:, s], raw[:, j])
    return sim


def score(sim: np.ndarray, log_prior: np.ndarray, prm: Params) -> Result:
    n, S = sim.shape
    has = np.isfinite(sim[0])
    z = np.where(has[None, :], sim / prm.temperature, -1e9)
    lp = log_softmax(z, axis=1)                                   # (n, S)
    fused = prm.photo_alpha * lp.sum(0) + prm.prior_beta * log_prior
    fused = np.where(has, fused, -1e9)
    probs = np.exp(log_softmax(fused))
    order = list(np.argsort(-probs, kind="stable"))
    top = order[0]
    second = order[1] if S > 1 else top
    per_photo_top = np.argmax(z, axis=1)
    agreement = float(np.mean(per_photo_top == top))
    mean_top_sim = float(np.mean(sim[:, top]))

    p1 = float(probs[top])
    p2 = float(probs[second]) if second != top else 0.0
    if mean_top_sim < prm.open_set_threshold:
        tier = UNKNOWN
    elif p1 >= prm.high_threshold and (p1 - p2) >= prm.margin and (n == 1 or agreement >= 0.5):
        tier = HIGH
    elif p1 >= prm.medium_threshold:
        tier = MEDIUM
    else:
        tier = LOW
    return Result(order, probs, tier, int(round(100 * p1)), agreement, mean_top_sim)
