import numpy as np

from openwild_pipeline.evaluate import leave_one_out, write_report


def test_leave_one_out_separable_and_confused(tmp_path):
    rng = np.random.default_rng(0)
    a = rng.normal(size=8)
    b = rng.normal(size=8)
    embs = np.stack([a + rng.normal(scale=0.05, size=8) for _ in range(3)] +
                    [b + rng.normal(scale=0.05, size=8) for _ in range(3)] +
                    [a + rng.normal(scale=0.05, size=8)])  # a lone photo of 'C' that looks like 'A'
    labels = ["A"] * 3 + ["B"] * 3 + ["C"]
    r = leave_one_out(labels, embs)
    assert r["photos_tested"] == 6 and r["top_k"]["top1"] == 1.0  # C has one photo: not testable
    labels2 = ["A", "A", "B", "B", "B", "A", "C"]
    r2 = leave_one_out(labels2, embs)
    assert r2["top_k"]["top1"] < 1.0 and r2["most_confused"]
    write_report(r2, tmp_path, "XX")
    assert (tmp_path / "ACCURACY.md").read_text().startswith("# Photo-ID accuracy: XX")
