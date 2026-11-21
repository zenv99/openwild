"""The global deadly collection: valid warnings, curated text kept, photos shipped."""
import json
import sqlite3
from pathlib import Path

from PIL import Image

from openwild_pipeline.content import load_curated
from openwild_pipeline.deadly import aliases_of, apply_to_pack, content_entry, load_species, validate_collection
from openwild_pipeline.pack import SpeciesRecord, build_pack


def test_collection_is_valid_and_covers_curated_deadly_species():
    problems = validate_collection()
    assert problems == [], "\n".join(problems)
    rows = load_species()
    assert len(rows) >= 200
    names: set[str] = set()
    kingdoms = set()
    for row in rows:
        names |= aliases_of(row)
        kingdoms.add(row["kingdom"])
        for err in []:
            pass
        entry = content_entry(row)
        assert entry["toxicity"] == "deadly"
        assert entry["sources"]
    assert kingdoms == {"Plantae", "Fungi"}
    missing = []
    for key, entry in load_curated().items():
        if entry.get("toxicity") == "deadly" or entry.get("edibility") == "deadly":
            if key not in names:
                missing.append(key)
    assert missing == []


def test_world_pack_lists_deadly_species_and_keeps_curated_wording(tmp_path: Path, monkeypatch):
    monkeypatch.setattr("openwild_pipeline.deadly.load_photos", lambda path=None: {})
    curated = load_curated()
    out = build_pack(
        tmp_path / "WORLD.owpack", "WORLD", "World", 1,
        [SpeciesRecord("Musa acuminata", "Plantae")],
        curated, guide_only=True, include_deadly=True,
    )
    con = sqlite3.connect(out)
    names = {n for (n,) in con.execute("SELECT scientific_name FROM species")}
    assert "Musa acuminata" in names
    assert "Amanita phalloides" in names
    assert "Cicuta douglasii" in names
    # Not duplicated when the region already has the species.
    assert sum(1 for n in names if n == "Amanita phalloides") == 1

    def content(name: str) -> dict:
        raw = con.execute(
            "SELECT json FROM content c JOIN species s ON s.id=c.species_id WHERE s.scientific_name=?",
            (name,),
        ).fetchone()[0]
        return json.loads(raw)

    # Hand-curated cassava stays a prepared food with its own warning, not a rewritten deadly row.
    cassava = content("Manihot esculenta")
    assert cassava["edibility"] == "edible_with_preparation"
    assert cassava["toxicity"] == "serious"
    # A species that exists only in the collection is badged deadly.
    water = content("Cicuta douglasii")
    assert water["toxicity"] == "deadly"
    assert water["edibility"] == "deadly"
    assert water["if_eaten"]["harms"]["what_to_do"]
    con.close()


def test_deadly_photo_is_packed_for_world(tmp_path: Path, monkeypatch):
    img = tmp_path / "leaf.jpg"
    Image.new("RGB", (360, 240), (20, 110, 40)).save(img, "JPEG")
    monkeypatch.setattr("openwild_pipeline.deadly.load_photos", lambda path=None: {
        "cicuta douglasii": [{
            "scientific_name": "Cicuta douglasii",
            "file": str(img),
            "license": "cc-by-sa",
            "observer": "Ada Lovelace",
            "page": "https://commons.wikimedia.org/wiki/File:Cicuta_douglasii.jpg",
            "stage": "leaves",
        }],
    })
    out = build_pack(
        tmp_path / "WORLD.owpack", "WORLD", "World", 1, [], {},
        guide_only=True, include_deadly=True,
    )
    con = sqlite3.connect(out)
    row = con.execute(
        "SELECT p.license, p.observer, p.stage, length(p.image) FROM photo p "
        "JOIN species s ON s.id=p.species_id WHERE s.scientific_name='Cicuta douglasii'"
    ).fetchone()
    assert row[0] == "cc-by-sa"
    assert row[1] == "Ada Lovelace"
    assert row[2] == "leaves"
    assert row[3] > 100
    con.close()


def test_inject_adds_a_deadly_species_to_an_existing_pack(tmp_path: Path, monkeypatch):
    img = tmp_path / "cap.jpg"
    Image.new("RGB", (240, 240), (180, 170, 80)).save(img, "JPEG")
    monkeypatch.setattr("openwild_pipeline.deadly.load_species", lambda path=None: [{
        "scientific_name": "Amanita phalloides",
        "kingdom": "Fungi",
        "family": "Amanitaceae",
        "genus": "Amanita",
        "common_name": "death cap",
        "route": "ingestion",
        "toxic_parts": "whole mushroom",
        "toxin": "amatoxins",
        "severity": "Frequently fatal.",
        "symptoms": "Severe vomiting, then liver failure.",
        "onset": "Usually 6–24 hours after eating.",
        "what_to_do": "Seek emergency medical care immediately.",
        "psychoactive": "0",
        "edibility": "deadly",
        "toxicity": "deadly",
        "source": "Wikipedia: Amanita phalloides",
        "aliases": "",
    }])
    monkeypatch.setattr("openwild_pipeline.deadly.load_photos", lambda path=None: {
        "amanita phalloides": [{
            "scientific_name": "Amanita phalloides",
            "file": str(img),
            "license": "cc0",
            "observer": "Ada Lovelace",
            "page": "https://commons.wikimedia.org/wiki/File:Amanita_phalloides.jpg",
            "stage": "field",
        }],
    })
    pack = build_pack(
        tmp_path / "WORLD.owpack", "WORLD", "World", 1,
        [SpeciesRecord("Musa acuminata", "Plantae")], {}, guide_only=True, include_deadly=False,
    )
    from openwild_pipeline.deadly import insert_into_pack

    stats = insert_into_pack(pack)
    assert stats["species_added"] == 1
    assert stats["photos_added"] == 1
    con = sqlite3.connect(pack)
    assert con.execute("SELECT COUNT(*) FROM species").fetchone()[0] == 2
    con.close()


def test_species_without_their_own_photo_show_a_labeled_example_and_no_fingerprint(tmp_path: Path, monkeypatch):
    from openwild_pipeline.deadly import load_photos, load_species

    photos = load_photos()
    expect = {
        "amanita subpallidorosea": "Amanita virosa",
        "lepiota locanensis": "Lepiota brunneoincarnata",
    }
    for name, depicts in expect.items():
        rows = photos[name]
        assert rows and all(r["stage"] == "example" for r in rows)
        assert all("example: " + depicts in r["observer"] for r in rows)
    wanted = [r for r in load_species() if r["scientific_name"].lower() in expect]
    monkeypatch.setattr("openwild_pipeline.deadly.load_species", lambda path=None: wanted)
    monkeypatch.setattr("openwild_pipeline.deadly.load_photos", lambda path=None: {k: photos[k] for k in expect})
    out = build_pack(
        tmp_path / "WORLD.owpack", "WORLD", "World", 1, [], {},
        guide_only=True, include_deadly=True,
    )
    con = sqlite3.connect(out)
    assert con.execute("SELECT COUNT(*) FROM prototype").fetchone()[0] == 0
    stages = dict(con.execute(
        "SELECT s.scientific_name, p.stage FROM photo p JOIN species s ON s.id=p.species_id"
    ))
    assert stages == {
        "Amanita subpallidorosea": "example",
        "Lepiota locanensis": "example",
    }
    con.close()


def test_apply_does_not_duplicate_an_alias(monkeypatch):
    monkeypatch.setattr("openwild_pipeline.deadly.load_photos", lambda path=None: {})
    species, _ = apply_to_pack(
        [SpeciesRecord("Thevetia peruviana", "Plantae")], {},
    )
    names = [r.scientific_name for r in species]
    assert "Thevetia peruviana" in names
    assert "Cascabela thevetia" not in names
