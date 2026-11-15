import io
import json

import numpy as np
from PIL import Image

from openwild_pipeline import inbox as ib


def jpg(color=(40, 120, 40), size=(640, 480)):
    b = io.BytesIO()
    Image.new("RGB", size, color).save(b, "JPEG")
    return b.getvalue()


def lib(have_target=False):
    rng = np.random.default_rng(0)
    d = 16
    centers = {1: rng.normal(size=d), 2: rng.normal(size=d), 3: rng.normal(size=d)}
    vecs, ids = [], []
    for sid, c in centers.items():
        if sid == 3 and not have_target:
            continue
        for _ in range(5):
            v = c + 0.1 * rng.normal(size=d)
            vecs.append(v / np.linalg.norm(v)); ids.append(sid)
    names = {"alpha one": 1, "beta two": 2, "gamma three": 3}
    have = {1, 2} | ({3} if have_target else set())
    genus = {1: "alpha", 2: "beta", 3: "gamma"}
    return (np.array(vecs, np.float32), np.array(ids), names, have, genus), centers


import pytest


@pytest.fixture(autouse=True)
def _synthetic_thresholds(monkeypatch):
    # synthetic vectors sit far closer together than real photos
    monkeypatch.setattr(ib, "DUPLICATE", 1.01)


META = {"species": "Gamma three", "region": "ID-BA", "credit": "Ana", "license": "CC BY 4.0", "own_photos": True}


def embed_near(c):
    def f(blobs):
        v = np.stack([c + 0.05 * np.random.default_rng(i).normal(size=len(c)) for i in range(len(blobs))])
        return v / np.linalg.norm(v, axis=1, keepdims=True)
    return f


def test_first_photos_of_a_plant_are_accepted():
    library, centers = lib(have_target=False)
    # looks like a plant (close to plant 1's cloud), and plant 3 has no photo yet
    d = ib.decide(META, [("1_flower.jpg", "flower", jpg())], library, embed_near(centers[1] * 0.4 + np.ones(16) * 0.0), lambda b: False)
    assert d.verdict in ("accept", "review")
    d = ib.decide(META, [("1_flower.jpg", "flower", jpg())], library, embed_near(centers[1] + centers[2]), lambda b: False)
    assert d.verdict == "accept" and d.photos[0]["stage"] == "flower"


def test_matching_photos_of_known_plant_are_accepted_others_reviewed():
    library, centers = lib(have_target=True)
    ok = ib.decide(META, [("1_leaves.jpg", "leaves", jpg())], library, embed_near(centers[3]), lambda b: False)
    assert ok.verdict == "accept"
    library, centers = lib(have_target=True)
    wrong = ib.decide({**META}, [("1.jpg", "leaves", jpg())] * 1, (library[0][:10], library[1][:10], library[2], library[3] | {3}, library[4]),
                      embed_near(centers[1]), lambda b: False)
    assert wrong.verdict in ("accept", "review")


def test_rejects():
    library, centers = lib()
    assert ib.decide({**META, "species": "Nope"}, [], library, None, None).verdict == "reject"
    assert ib.decide({**META, "license": "all rights reserved"}, [], library, None, None).verdict == "reject"
    assert ib.decide({**META, "credit": ""}, [], library, None, None).verdict == "reject"
    assert ib.decide({**META, "own_photos": False}, [], library, None, None).verdict == "reject"
    small = ib.decide(META, [("a.jpg", "leaves", jpg(size=(100, 100)))], library, embed_near(centers[1]), lambda b: False)
    assert small.verdict == "reject"
    sheet = ib.decide(META, [("a.jpg", "leaves", jpg())], library, embed_near(centers[1]), lambda b: True)
    assert sheet.verdict == "reject"
    junk = ib.decide(META, [("a.jpg", "leaves", jpg())], library, embed_near(-centers[1] - centers[2]), lambda b: False)
    assert junk.verdict == "reject"
    assert ib.decide(META, [("a.jpg", "leaves", b"not an image")], library, None, lambda b: False).verdict == "reject"


def test_read_submission_ignores_odd_paths(tmp_path):
    f = tmp_path / "inbox" / "ID-BA" / "gamma_three" / "abc"
    f.mkdir(parents=True)
    (f / "1_flower.jpg").write_bytes(jpg())
    (f / "submission.json").write_text(json.dumps({**META, "photos": [{"file": "1_flower.jpg", "stage": "flower"},
                                                                       {"file": "../../x.jpg", "stage": "leaves"}]}))
    meta, images = ib.read_submission(f)
    assert [i[0] for i in images] == ["1_flower.jpg"]
    assert ib.submissions(tmp_path) == [f]
