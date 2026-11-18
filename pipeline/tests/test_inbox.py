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


def test_feedback_votes_hide_wrong_photos(tmp_path, monkeypatch):
    monkeypatch.setattr(ib, "COMMUNITY", tmp_path / "community")
    inbox = tmp_path / "inbox"
    for k in range(4):
        f = inbox / "feedback" / "ID-BA" / "gamma_three" / f"vote_{k}.json"
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps({"kind": "vote", "region": "ID-BA", "species": "Gamma three", "correct": k == 0, "photo": "http://x/1"}))
    (inbox / "feedback" / "ID-BA" / "gamma_three" / "review_a.json").write_text(json.dumps(
        {"kind": "review", "region": "ID-BA", "species": "Gamma three", "rating": 4, "text": "Local name: daun x @someone"}))
    files, by = ib.collect_feedback(inbox, "2026-11-16")
    assert len(files) == 5 and len(by["ID-BA"]) == 5
    ib.save_feedback("ID-BA", by["ID-BA"])
    assert ib.hidden_photos("ID-BA") == {"http://x/1"}     # 3 no, 1 yes
    ib.save_feedback("ID-BA", by["ID-BA"])                 # weekly re-read must not double-count
    assert ib.hidden_photos("ID-BA") == {"http://x/1"}
    (tmp_path / "community" / "ID-BA" / "hide.txt").write_text("# maintainer\nhttp://x/kept\n")
    assert ib.hidden_photos("ID-BA") == {"http://x/1", "http://x/kept"}
    d = ib.digest(by)
    assert "Local name" in d and "@someone" not in d


def test_names_merge_keeps_every_local_name():
    from openwild_pipeline import names as nm
    m = nm.merge({"ind": "Biduri", "jav": "Sidogori/Sidaguri"}, {"ind": ["Biduri", "Widuri"], "ban": ["Jaba"]})
    assert m["ind"] == "Biduri; Widuri" and m["ban"] == "Jaba" and m["jav"] == "Sidogori/Sidaguri"
    assert nm.best_common_name(m, nm.local_languages("ID-BA")) == "Jaba"


def test_suggested_names_stay_untrusted_until_reviewed(tmp_path, monkeypatch):
    monkeypatch.setattr(ib, "COMMUNITY", tmp_path / "community")
    raw = {
        "kind": "wrong", "predicted": "Nephrolepis cordifolia", "guess": "bird's nest fern",
        "suggested": "Asplenium nidus", "note": "in a pot indoors", "status": "trusted",
        "top_matches": "Nephrolepis cordifolia 40%", "model": "ow-dinov2s-384-v1",
        "embeddings": [[0.1, 0.2]], "share_location": False, "photos": [],
    }
    row = ib.candidate_from_report(raw, "ID-BA", "asplenium nidus", "reports/ID-BA/x/abc", "2026-11-18")
    assert row["status"] == "review"
    assert row["matched"] == "asplenium nidus"
    assert row["suggested"] == "Asplenium nidus"
    assert row["share_location"] == "no"
    assert "lat" not in row
    ib.save_candidates("ID-BA", [row])
    assert ib.fingerprint_jobs("ID-BA") == []
    reviewed = dict(row, status="reviewed")
    ib.save_candidates("ID-BA", [reviewed])
    jobs = ib.fingerprint_jobs("ID-BA")
    assert len(jobs) == 1 and jobs[0]["matched"] == "asplenium nidus" and jobs[0]["status"] == "reviewed"
    # a reviewed row with no resolved species is not a fingerprint job
    ib.save_candidates("ID-BA", [dict(row, status="reviewed", matched="", source="reports/ID-BA/x/other")])
    assert len(ib.fingerprint_jobs("ID-BA")) == 1
    ib.save_candidates("ID-BA", [row])
    assert len(ib.fingerprint_jobs("ID-BA")) == 1


def test_feedback_folder_wrong_id_becomes_a_candidate(tmp_path, monkeypatch):
    monkeypatch.setattr(ib, "COMMUNITY", tmp_path / "community")
    inbox = tmp_path / "inbox"
    f = inbox / "feedback" / "ID-BA" / "asplenium_nidus" / "wrong_abc.json"
    f.parent.mkdir(parents=True)
    f.write_text(json.dumps({
        "kind": "wrong", "region": "ID-BA", "species": "Asplenium nidus",
        "guess": "Asplenium nidus", "predicted": "Microsorum musifolium", "note": "houseplant",
    }))
    rows = ib.wrong_id_feedback(inbox, "2026-11-18")
    assert len(rows) == 1 and rows[0][0] == "ID-BA" and rows[0][1]["status"] == "review"
    assert ib.save_candidates("ID-BA", [rows[0][1]]) == 1
    assert ib.save_candidates("ID-BA", [rows[0][1]]) == 0


def test_reports_resolve_names_and_confusions(tmp_path, monkeypatch):
    monkeypatch.setattr(ib, "COMMUNITY", tmp_path / "community")
    names = {"calotropis gigantea": 1, "datura metel": 2}
    aka = {"biduri": "calotropis gigantea", "kecubung": "datura metel"}
    assert ib.resolve_name("Biduri", names, aka) == "calotropis gigantea"
    assert ib.resolve_name("Datura metel", names, aka) == "datura metel"
    assert ib.resolve_name("no idea", names, aka) is None
    rows = [{"date": "d", "kind": "correction", "predicted": "Brugmansia suaveolens", "target": "kecubung",
             "matched": "datura metel", "verdict": "accept", "photos": 2, "top_matches": "", "pack_version": "1"}] * 3
    ib.save_reports("ID-BA", rows)
    assert ib.confusions("ID-BA") == [("brugmansia suaveolens", "datura metel", 3)]
