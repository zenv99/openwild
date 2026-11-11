"""Fit temperature, photo_alpha and the open-set threshold on held-out reference photos.

Method: split each species' photos into prototype-building and held-out sets, then
  * temperature: grid search minimising single-photo negative log-likelihood,
  * photo_alpha: grid search minimising NLL on random same-species photo pairs/triples,
  * open_set_threshold: the 95th percentile of best-match similarity for species *left out* of the
    library, so ≤5% of unknown species are reported as a match.
"""
from __future__ import annotations

import numpy as np

from .confidence import log_softmax
from .prototypes import l2n, spherical_kmeans, n_prototypes


def _split(taxa: np.ndarray, rng, holdout: float = 0.2):
    train_idx, test_idx = [], []
    for t in np.unique(taxa):
        idx = np.flatnonzero(taxa == t)
        rng.shuffle(idx)
        k = max(1, int(len(idx) * holdout)) if len(idx) >= 5 else 0
        test_idx.extend(idx[:k])
        train_idx.extend(idx[k:])
    return np.array(train_idx), np.array(test_idx)


def calibrate(embs: np.ndarray, taxa: np.ndarray, seed: int = 0) -> dict:
    rng = np.random.default_rng(seed)
    embs = l2n(embs.astype(np.float32))
    tr, te = _split(taxa, rng)
    species = np.unique(taxa[tr])
    sp_index = {t: i for i, t in enumerate(species)}

    # Prototypes from training split; hold out 10% of species entirely for the open-set estimate.
    unknown = set(rng.choice(species, size=max(1, len(species) // 10), replace=False).tolist())
    protos, owner = [], []
    for t in species:
        if t in unknown:
            continue
        x = embs[tr][taxa[tr] == t]
        for c in spherical_kmeans(x, n_prototypes(len(x))):
            protos.append(c)
            owner.append(sp_index[t])
    P = np.stack(protos)
    owner = np.array(owner)
    S = len(species)

    def sim_matrix(x):
        raw = x @ P.T
        sim = np.full((len(x), S), -np.inf)
        for j in range(len(P)):
            sim[:, owner[j]] = np.maximum(sim[:, owner[j]], raw[:, j])
        return sim

    known_te = np.array([i for i in te if taxa[i] not in unknown])
    unk_te = np.array([i for i in te if taxa[i] in unknown])
    sim_known = sim_matrix(embs[known_te])
    y = np.array([sp_index[t] for t in taxa[known_te]])
    has = np.isfinite(sim_known[0])

    best_t, best_nll = 0.03, np.inf
    for T in (0.01, 0.015, 0.02, 0.03, 0.04, 0.05, 0.07, 0.1):
        lp = log_softmax(np.where(has, sim_known / T, -1e9), axis=1)
        nll = -float(np.mean(lp[np.arange(len(y)), y]))
        if nll < best_nll:
            best_t, best_nll = T, nll

    lp = log_softmax(np.where(has, sim_known / best_t, -1e9), axis=1)
    best_a, best_nll_a = 0.6, np.inf
    by_species: dict[int, list[int]] = {}
    for row, s in enumerate(y):
        by_species.setdefault(int(s), []).append(row)
    groups = [rows for rows in by_species.values() if len(rows) >= 2]
    for a in (0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 1.0):
        nlls = []
        for rows in groups:
            pick = rng.choice(rows, size=min(3, len(rows)), replace=False)
            fused = log_softmax(a * lp[pick].sum(0))
            nlls.append(-fused[y[pick[0]]])
        if nlls and np.mean(nlls) < best_nll_a:
            best_a, best_nll_a = a, float(np.mean(nlls))

    if len(unk_te):
        best_unknown = np.max(np.where(np.isfinite(sim_matrix(embs[unk_te])), sim_matrix(embs[unk_te]), -1), axis=1)
        open_set = float(np.quantile(best_unknown, 0.95))
    else:
        open_set = 0.30
    top1 = float(np.mean(np.argmax(lp, axis=1) == y)) if len(y) else 0.0
    return {
        "temperature": best_t,
        "photo_alpha": best_a,
        "open_set_threshold": round(open_set, 4),
        "heldout_top1": round(top1, 4),
        "heldout_n": int(len(y)),
    }
