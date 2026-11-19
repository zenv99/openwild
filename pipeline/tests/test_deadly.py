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


def test_apply_does_not_duplicate_an_alias(monkeypatch):
    monkeypatch.setattr("openwild_pipeline.deadly.load_photos", lambda path=None: {})
    species, _ = apply_to_pack(
        [SpeciesRecord("Thevetia peruviana", "Plantae")], {},
    )
    names = [r.scientific_name for r in species]
    assert "Thevetia peruviana" in names
    assert "Cascabela thevetia" not in names
