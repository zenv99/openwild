"""Confidence scoring. The FIXTURE values are mirrored in android/app/src/test/.../ConfidenceTest.kt."""
import json
from pathlib import Path

import numpy as np

from openwild_pipeline.confidence import HIGH, LOW, MEDIUM, UNKNOWN, Params, prior, score, species_similarity

FIXTURE = Path(__file__).parent / "fixtures" / "confidence_case.json"


def _run(case):
    protos = np.array(case["protos"], dtype=np.float64)
    photos = np.array(case["photos"], dtype=np.float64)
    photos /= np.linalg.norm(photos, axis=1, keepdims=True)
    prm = Params(**case.get("params", {}))
    sim = species_similarity(
        photos, protos, np.array(case["proto_species"]), np.array(case["proto_is_text"]),
        len(case["obs_counts"]), prm.text_proto_penalty,
    )
    lp = np.log(prior(np.array(case["obs_counts"]), np.array(case["month_freq"])))
    return score(sim, lp, prm)


def test_fixture_matches_expected():
    case = json.loads(FIXTURE.read_text())
    r = _run(case)
    exp = case["expected"]
    assert r.order[0] == exp["top"]
    assert r.tier == exp["tier"]
    assert r.score == exp["score"]
    np.testing.assert_allclose(r.probs, exp["probs"], atol=1e-6)


def test_unknown_when_similarity_low():
    case = json.loads(FIXTURE.read_text())
    case["photos"] = [[0, 0, 0, 1]]
    assert _run(case).tier == UNKNOWN


def test_more_agreeing_photos_raise_confidence():
    case = json.loads(FIXTURE.read_text())
    case["photos"] = [[1, 0.25, 0, 0]]
    one = _run(case)
    case["photos"] = [[1, 0.25, 0, 0], [1, 0.2, 0.05, 0], [0.95, 0.3, 0, 0]]
    three = _run(case)
    assert three.probs[three.order[0]] > one.probs[one.order[0]]


def test_tiers_ordered():
    assert {HIGH, MEDIUM, LOW, UNKNOWN} == {"HIGH", "MEDIUM", "LOW", "UNKNOWN"}
