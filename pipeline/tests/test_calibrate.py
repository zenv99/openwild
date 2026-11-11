import numpy as np

from openwild_pipeline.calibrate import calibrate
from openwild_pipeline.prototypes import l2n


def test_calibrate_on_synthetic_species():
    rng = np.random.default_rng(0)
    centres = l2n(rng.normal(size=(30, 64)))
    embs, taxa = [], []
    for t, c in enumerate(centres):
        embs.append(l2n(c + 0.08 * rng.normal(size=(25, 64))))
        taxa += [t] * 25
    params = calibrate(np.vstack(embs), np.array(taxa))
    assert params["heldout_top1"] > 0.95
    assert 0.0 < params["open_set_threshold"] < 1.0
    assert params["temperature"] > 0 and 0 < params["photo_alpha"] <= 1
