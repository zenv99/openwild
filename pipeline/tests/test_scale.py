"""Scaling features: genus/family safety rules and PCA projection."""
import json
import sqlite3

import numpy as np

from openwild_pipeline import config
from openwild_pipeline.content import load_curated, load_group_rules, resolve, validate_rule
from openwild_pipeline.pack import SpeciesRecord, build_pack
from openwild_pipeline.projection import apply, explained_variance, fit
from openwild_pipeline.prototypes import l2n


def test_group_rules_valid_and_numerous():
    rules = load_group_rules()
    assert len(rules) >= 100
    assert ("genus", "amanita") in rules and ("family", "apiaceae") in rules


def test_rule_validator_rejects_edible_rule():
    assert validate_rule({"taxon": "X", "rank": "genus", "kingdom": "Plantae", "toxicity": "none",
                          "warning": "w", "onset": "o", "what_to_do": "d", "source": "s"})


def test_resolution_order():
    curated, rules = load_curated(), load_group_rules()
    assert resolve(curated, rules, "Amanita phalloides", "Amanita", "Amanitaceae")["level"] == "species"
    unknown_amanita = resolve(curated, rules, "Amanita imaginaria", "Amanita", "Amanitaceae")
    assert unknown_amanita["level"] == "genus" and unknown_amanita["toxicity"] == "deadly"
    assert unknown_amanita["edibility"] == "unknown"
    assert resolve(curated, rules, "Anethum graveolens", "Anethum", "Apiaceae")["level"] == "family"
    assert resolve(curated, rules, "Quercus alba", "Quercus", "Fagaceae") is None
    assert "legal_warning" in resolve(curated, rules, "Psilocybe azurescens", "Psilocybe", None)


def test_projection_keeps_low_rank_structure():
    rng = np.random.default_rng(0)
    basis = l2n(rng.normal(size=(32, config.EMBED_DIM)))
    x = l2n(rng.normal(size=(2000, 32)) @ basis + 0.01 * rng.normal(size=(2000, config.EMBED_DIM)))
    w, mean = fit(x, k=64)
    assert w.shape == (64, config.EMBED_DIM)
    assert explained_variance(x, w, mean) > 0.95
    z = apply(x[:5], w, mean)
    assert np.allclose(np.linalg.norm(z, axis=1), 1.0, atol=1e-5)
    assert np.allclose(apply(x[0], w, mean), z[0], atol=1e-6)


def test_pack_with_projection_and_group_rules(tmp_path):
    rng = np.random.default_rng(1)
    embs = l2n(rng.normal(size=(400, config.EMBED_DIM)))
    w, mean = fit(embs, k=64)
    recs = [
        SpeciesRecord("Amanita imaginaria", "Fungi", 1, genus="Amanita", family="Amanitaceae", embeddings=embs[:20]),
        SpeciesRecord("Quercus alba", "Plantae", 2, genus="Quercus", family="Fagaceae", embeddings=embs[20:40]),
    ]
    out = build_pack(tmp_path / "S-v1.owpack", "S", "Scale", 1, recs, load_curated(), projection=(w, mean))
    con = sqlite3.connect(out)
    meta = dict(con.execute("SELECT key, value FROM meta"))
    assert meta["embedding_dim"] == "64" and meta["input_dim"] == str(config.EMBED_DIM)
    rows, cols, mat, mu = con.execute("SELECT rows, cols, matrix, mean FROM projection").fetchone()
    assert (rows, cols) == (64, config.EMBED_DIM) and len(mat) == 64 * config.EMBED_DIM * 4 and len(mu) == config.EMBED_DIM * 4
    assert all(len(b) == 64 for (b,) in con.execute("SELECT vec FROM prototype"))
    amanita = json.loads(con.execute("SELECT json FROM content WHERE species_id = 1").fetchone()[0])
    assert amanita["level"] == "genus" and amanita["toxicity"] == "deadly"
    oak = json.loads(con.execute("SELECT json FROM content WHERE species_id = 2").fetchone()[0])
    assert oak["edibility"] == "unknown"
