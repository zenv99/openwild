import json
import sqlite3

import numpy as np
import pytest

from openwild_pipeline import config
from openwild_pipeline.content import ContentError, load_curated, validate
from openwild_pipeline.pack import SpeciesRecord, build_pack, months_blob, update_index
from openwild_pipeline.prototypes import dequantize, l2n, n_prototypes, quantize, spherical_kmeans
from openwild_pipeline.regions import load_regions, locate


def test_quantize_roundtrip_preserves_direction():
    v = l2n(np.random.default_rng(0).normal(size=config.EMBED_DIM))
    blob, scale = quantize(v)
    assert len(blob) == config.EMBED_DIM
    w = dequantize(blob, scale)
    assert float(v @ l2n(w)) > 0.999


def test_kmeans_finds_two_modes():
    rng = np.random.default_rng(1)
    a = l2n(np.array([1.0, 0, 0]) + 0.05 * rng.normal(size=(20, 3)))
    b = l2n(np.array([0, 1.0, 0]) + 0.05 * rng.normal(size=(20, 3)))
    c = spherical_kmeans(np.vstack([a, b]), 2)
    best = sorted(np.argmax(c, axis=1).tolist())
    assert best == [0, 1]


def test_n_prototypes_bounds():
    assert n_prototypes(1) == 1
    assert n_prototypes(16) == 2
    assert n_prototypes(1000) == config.MAX_PROTOTYPES


def test_months_blob():
    assert months_blob([0] * 12) == bytes([128] * 12)
    b = months_blob([0, 5, 10] + [0] * 9)
    assert b[2] == 255 and b[1] == 128 and b[0] == 0


def test_build_pack_schema_and_content(tmp_path):
    rng = np.random.default_rng(2)
    curated = load_curated()
    recs = [
        SpeciesRecord("Amanita phalloides", "Fungi", 1, obs_count=50, months=[1] * 12,
                      embeddings=l2n(rng.normal(size=(30, config.EMBED_DIM))),
                      attributions=[{"observer": "x", "license": "cc-by", "url": "https://example.org/1"}]),
        SpeciesRecord("Volvariella volvacea", "Fungi", 2, obs_count=20,
                      embeddings=l2n(rng.normal(size=(12, config.EMBED_DIM)))),
        SpeciesRecord("Psilocybe cubensis", "Fungi", 3, obs_count=5,
                      text_embedding=l2n(rng.normal(size=config.EMBED_DIM))),
        SpeciesRecord("No data species", "Plantae", 4),  # no photos, no text -> skipped
    ]
    out = build_pack(tmp_path / "T-v1.owpack", "T", "Test", 1, recs, curated)
    con = sqlite3.connect(out)
    meta = dict(con.execute("SELECT key, value FROM meta"))
    assert meta["model_id"] == config.MODEL_ID and meta["region_id"] == "T"
    assert con.execute("SELECT count(*) FROM species").fetchone()[0] == 3
    kinds = dict(con.execute("SELECT kind, count(*) FROM prototype GROUP BY kind"))
    assert kinds["image"] >= 2 and kinds["text"] == 1
    death = json.loads(con.execute(
        "SELECT json FROM content c JOIN species s ON s.id=c.species_id WHERE s.scientific_name='Amanita phalloides'"
    ).fetchone()[0])
    assert death["toxicity"] == "deadly" and death["if_eaten"]["harms"]["onset"]
    psy = json.loads(con.execute(
        "SELECT json FROM content c JOIN species s ON s.id=c.species_id WHERE s.scientific_name='Psilocybe cubensis'"
    ).fetchone()[0])
    assert "legal_warning" in psy
    linked = con.execute(
        "SELECT lookalike_species_id FROM lookalike WHERE lookalike_name='Volvariella volvacea'"
    ).fetchone()[0]
    assert linked is not None
    for blob, in con.execute("SELECT vec FROM prototype"):
        assert len(blob) == config.EMBED_DIM
    idx = update_index(tmp_path / "index.json", out, "https://example.org/T-v1.owpack")
    assert idx["packs"][0]["species_count"] == 3 and len(idx["packs"][0]["sha256"]) == 64


def test_curated_content_is_valid():
    assert len(load_curated()) >= 140


@pytest.mark.parametrize("bad_text", [
    "A typical dose is 2 g.",
    "Brew into a tea for effects.",
    "Microdosing is popular.",
])
def test_validator_rejects_consumption_instructions(bad_text):
    entry = {
        "scientific_name": "Psilocybe test", "edibility": "psychoactive", "toxicity": "mild", "psychoactive": True,
        "if_eaten": {"harms": {"symptoms": "nausea"}, "feeling": {"effects": bad_text}},
        "sources": ["x"],
    }
    assert validate(entry)


def test_validator_requires_onset_for_deadly():
    entry = {"scientific_name": "X", "edibility": "deadly", "toxicity": "deadly",
             "if_eaten": {"harms": {"symptoms": "s", "what_to_do": "w"}}, "sources": ["x"]}
    assert any("onset" in e for e in validate(entry))


def test_load_curated_strict_raises(tmp_path):
    (tmp_path / "bad.yaml").write_text("- {scientific_name: X, edibility: edible, toxicity: deadly, sources: [a]}\n")
    with pytest.raises(ContentError):
        load_curated(tmp_path)


def test_regions_locate():
    regions = load_regions()
    assert locate(regions, 121.0, 14.6).id == "PH"         # Manila
    assert locate(regions, 115.2, -8.65).id == "ID-BA"     # Bali
    assert locate(regions, 106.8, -6.2).id == "ID"         # Jakarta
    assert locate(regions, -122.4, 37.8).id == "US-CA"     # San Francisco
    assert locate(regions, -30.0, 0.0).id == "WORLD"       # mid-Atlantic -> world pack
    assert locate(regions, -74.0, 40.7).id == "US"         # New York
    assert locate(regions, -157.8, 21.3).id == "US-HI"     # Honolulu
    assert len(regions) >= 60


def test_build_pack_stores_display_photos(tmp_path):
    from PIL import Image

    img = tmp_path / "a.jpg"
    Image.new("RGB", (300, 200), (40, 160, 60)).save(img)
    recs = [SpeciesRecord(scientific_name="Musa acuminata", kingdom="Plantae",
                          photos=[{"path": str(img), "observer": "A", "license": "cc-by", "url": "https://x/1"},
                                  {"path": str(img), "observer": "B", "license": "cc0", "url": "https://x/2"},
                                  {"path": str(tmp_path / "missing.jpg"), "observer": "C", "license": "cc0", "url": "u"}])]
    out = build_pack(tmp_path / "P.owpack", "P", "P", 1, recs, {}, guide_only=True, photos_per_species=2)
    con = sqlite3.connect(out)
    rows = con.execute("SELECT rank, thumb, image, observer, license FROM photo ORDER BY rank").fetchall()
    assert [r[0] for r in rows] == [0, 1]
    thumb = Image.open(__import__("io").BytesIO(rows[0][1]))
    assert thumb.format == "WEBP" and thumb.size == (112, 112)
    assert Image.open(__import__("io").BytesIO(rows[0][2])).size == (300, 200)
    assert rows[0][3:] == ("A", "cc-by")
