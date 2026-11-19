"""Reference-photo selection, fungus safety text, and pack augmentation."""
import json
import sqlite3

import numpy as np
from PIL import Image

from openwild_pipeline.content import load_curated, load_group_rules
from openwild_pipeline.cultivated import cultivated_species_rows
from openwild_pipeline.evaluate import leave_one_out
from openwild_pipeline.pack import SpeciesRecord, build_pack
from openwild_pipeline.prototypes import l2n
from openwild_pipeline.references import (
    augment_pack,
    choose_references,
    content_for_new_species,
    leave_one_out_matrix,
)
from openwild_pipeline.regions import union_species_rows


def _photo(i, view, lic, obs, observer="Ada"):
    return {
        "url": f"https://example.org/img/{i}.jpg",
        "page": f"https://www.inaturalist.org/photos/{i}",
        "license": lic,
        "observer": observer,
        "view": view,
        "obs": str(obs),
        "rank": 1,
    }


def test_southeast_asia_match_ignores_anguilla():
    from openwild_pipeline.references import _sea_text

    assert _sea_text("INTRODUCED (Anguilla AI ANGUILLA; Global Register of Introduced and Invasive Species - Anguilla)") is False
    assert _sea_text("INTRODUCED (Vietnam TDWG:VIE; The World Checklist of Vascular Plants (WCVP))") is True
    assert _sea_text("Philippines TDWG:PHI") is True


def test_choose_prefers_open_licences_and_splits_heldout():
    cands = []
    # Observation ids divisible by 5 are eligible for the held-out set.
    for i, view in enumerate(("pot", "leaves", "flower", "habit")):
        cands.append(_photo(i, view, "cc-by-nc", obs=10 + i))
        cands.append(_photo(100 + i, view, "cc-by", obs=20 + i, observer="Bea"))
    cands.append(_photo(7, "pot", "cc0", obs=5, observer="Cara"))
    cands.append({"url": "https://pfaf.org/x.jpg", "page": "https://pfaf.org/x", "license": "cc0",
                  "observer": "No", "view": "habit", "obs": "9", "rank": 1})
    refs, held = choose_references(
        cands, category="houseplant", existing_n=0, have_views=set(), existing_urls=set(), heldout_goal=1,
    )
    assert refs and held
    assert all(r["license"] in {"cc0", "cc-by", "cc-by-sa"} for r in refs)
    assert all("pfaf.org" not in r["url"] for r in refs + held)
    assert {r["obs"] for r in refs}.isdisjoint({h["obs"] for h in held})
    assert {r["view"] for r in refs} >= {"pot", "leaves"}


def test_new_fungi_do_not_gain_an_edible_claim():
    curated, rules = load_curated(), load_group_rules()
    edible = content_for_new_species(
        "Termitomyces eurrhizus", "Fungi", "Termitomyces", "Lyophyllaceae", "fungus", curated, rules,
    )
    assert edible["edibility"] == "unknown"
    assert "benefits" not in (edible.get("if_eaten") or {})
    toxic = content_for_new_species(
        "Chlorophyllum molybdites", "Fungi", "Chlorophyllum", "Agaricaceae", "fungus", curated, rules,
    )
    assert toxic["edibility"] == "toxic"
    assert "benefits" not in (toxic.get("if_eaten") or {})
    psi = content_for_new_species(
        "Psilocybe cubensis", "Fungi", "Psilocybe", "Hymenogastraceae", "fungus", curated, rules,
    )
    assert psi["edibility"] == "unknown"
    assert psi.get("psychoactive") is True
    assert "feeling" not in psi
    assert "dose" not in json.dumps(psi).lower()


def test_cultivated_rows_union_without_replacing_a_wild_name(tmp_path, monkeypatch):
    region = tmp_path / "ID"
    region.mkdir()
    (region / "cultivated.csv").write_text(
        "gbif_key,scientific_name,kingdom,family,genus,common_name,common_names,gbif_count,basis,citation\n"
        "1,Zamioculcas zamiifolia,Plantae,Araceae,Zamioculcas,ZZ,{},0,cultivated,https://www.gbif.org/species/1\n"
        "2,Monstera deliciosa,Plantae,Araceae,Monstera,Swiss cheese,{},3,cultivated,https://www.gbif.org/species/2\n",
        encoding="utf-8",
    )
    monkeypatch.setattr("openwild_pipeline.cultivated.config.DATA_REGIONS", tmp_path)
    rows = cultivated_species_rows("ID")
    assert rows[0]["citation"].startswith("https://www.gbif.org/")
    merged = union_species_rows(
        [{"scientific_name": "Monstera deliciosa", "gbif_count": "15"}], rows,
    )
    names = [r["scientific_name"] for r in merged]
    assert names.count("Monstera deliciosa") == 1
    assert merged[0]["gbif_count"] == "15"
    assert "Zamioculcas zamiifolia" in names


def test_leave_one_out_batch_matches_the_simple_scorer():
    rng = np.random.default_rng(4)
    labels = [f"sp{i // 3}" for i in range(900)]
    embs = l2n(rng.normal(size=(900, 16)).astype(np.float32))
    fast = leave_one_out_matrix(labels, embs, batch=128)
    slow = leave_one_out(labels, embs)
    assert fast["photos_tested"] == slow["photos_tested"]
    assert fast["top_k"] == slow["top_k"]


def test_augment_keeps_existing_species_and_refuses_edible_fungus_text(tmp_path):
    rng = np.random.default_rng(2)
    leaf = tmp_path / "leaf.jpg"
    Image.new("RGB", (280, 360), (30, 140, 50)).save(leaf)
    base = tmp_path / "base.owpack"
    build_pack(
        base, "ID", "Indonesia", 5,
        [SpeciesRecord(
            "Asplenium nidus", "Plantae", embeddings=l2n(rng.normal(size=(2, 384))),
            photos=[{"path": str(leaf), "observer": "Ada", "license": "cc-by", "url": "https://example.org/old"}],
            attributions=[{"observer": "Ada", "license": "cc-by", "url": "https://example.org/old"}],
        )],
        {}, guide_only=True, min_photos_for_image_protos=1,
    )
    cap = tmp_path / "cap.jpg"
    Image.new("RGB", (300, 300), (180, 160, 120)).save(cap)
    vec = l2n(rng.normal(size=384))
    additions = [
        {"scientific_name": "Asplenium nidus", "path": str(cap), "page": "https://example.org/new-fern",
         "license": "cc-by-sa", "observer": "Bea", "stage": "leaves", "embedding": vec, "category": "houseplant"},
        {"scientific_name": "Termitomyces eurrhizus", "path": str(cap), "page": "https://example.org/t1",
         "license": "cc0", "observer": "Cara", "stage": "field", "embedding": vec, "category": "fungus",
         "kingdom": "Fungi", "genus": "Termitomyces", "family": "Lyophyllaceae", "gbif_key": "123",
         "description": "Included so it can be recognised. No edibility is assessed here.",
         "common_name": "Termite mushroom", "common_names": {"eng": "Termite mushroom"}},
        {"scientific_name": "Termitomyces eurrhizus", "path": str(leaf), "page": "https://example.org/t2",
         "license": "cc-by", "observer": "Dan", "stage": "closeup", "embedding": l2n(rng.normal(size=384)),
         "category": "fungus", "kingdom": "Fungi", "genus": "Termitomyces", "family": "Lyophyllaceae",
         "gbif_key": "123", "description": "Included so it can be recognised. No edibility is assessed here.",
         "common_name": "Termite mushroom", "common_names": {"eng": "Termite mushroom"}},
    ]
    out = tmp_path / "out.owpack"
    stats = augment_pack(base, out, additions, version=9)
    assert stats["added_species"] == 1
    con = sqlite3.connect(out)
    names = {n for (n,) in con.execute("SELECT scientific_name FROM species")}
    assert names == {"Asplenium nidus", "Termitomyces eurrhizus"}
    fern_photos = con.execute(
        "SELECT COUNT(*) FROM photo p JOIN species s ON s.id = p.species_id WHERE s.scientific_name = ?",
        ("Asplenium nidus",),
    ).fetchone()[0]
    assert fern_photos == 2
    raw = con.execute(
        "SELECT c.json FROM content c JOIN species s ON s.id = c.species_id WHERE s.scientific_name = ?",
        ("Termitomyces eurrhizus",),
    ).fetchone()[0]
    body = json.loads(raw)
    assert body["edibility"] == "unknown"
    assert body["edibility"] != "edible"
    version = con.execute("SELECT value FROM meta WHERE key = 'pack_version'").fetchone()[0]
    assert version == "9"
    con.close()
