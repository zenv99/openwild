"""Name checks and the Indonesia pack fold. No network."""
import json
import sqlite3
from pathlib import Path

from PIL import Image

from openwild_pipeline.commercial_photos import starter_tier
from openwild_pipeline.danger_photos import binomial, content_tag, name_ok, wrong_species_title
from openwild_pipeline.id_merge import add_display_photos, merge_packs, prefer_content


def test_binomial_stops_at_rank_and_author():
    assert binomial("Amanita borneensis Corner") == "amanita borneensis"
    assert binomial("Cycas rumphii subsp. zeylanica") == "cycas rumphii"
    assert binomial("Amanita") == ""


def test_photo_names_keep_the_accepted_name_and_not_every_synonym():
    from openwild_pipeline.danger_photos import photo_names

    resolved = {
        "query": "Strychnos pubescens",
        "accepted": "Strychnos axillaris",
        "allowed": {"strychnos pubescens", "strychnos axillaris", "strychnos pubiflora", "strychnos wenzelii"},
    }
    assert photo_names(resolved) == {"strychnos pubescens", "strychnos axillaris"}


def test_name_ok_rejects_a_lookalike():
    allowed = {"amanita borneensis", "amanita punctata"}
    assert name_ok("Amanita borneensis", allowed)
    assert name_ok("Amanita punctata (Beeli) Corner", allowed)
    assert not name_ok("Amanita muscaria", allowed)
    assert not name_ok("Amanita", allowed)
    assert wrong_species_title("File:Amanita muscaria young.jpg", allowed, "Amanita")
    assert not wrong_species_title("File:Amanita borneensis cap.jpg", allowed, "Amanita")
    assert not wrong_species_title("File:young fruiting body.jpg", allowed, "Amanita")


def test_content_tag_and_starter_tier():
    assert content_tag({"toxicity": "deadly", "edibility": "unknown", "level": "genus"}) == "deadly"
    assert content_tag({"toxicity": "serious", "edibility": "toxic"}) == "toxic"
    assert content_tag({"toxicity": "mild", "edibility": "edible"}) == ""
    assert starter_tier({"toxicity": "serious", "edibility": "toxic"}) == "poison"
    assert starter_tier({"toxicity": "deadly", "edibility": "deadly"}) == "deadly"
    assert starter_tier({"toxicity": "none", "edibility": "edible"}) == "food"


def test_prefer_content_keeps_the_more_serious_and_the_more_specific():
    genus = {"toxicity": "deadly", "edibility": "unknown", "level": "genus"}
    species = {"toxicity": "deadly", "edibility": "deadly", "level": "species"}
    edible = {"toxicity": "none", "edibility": "edible", "level": "quick"}
    assert prefer_content(edible, genus) is genus
    assert prefer_content(species, genus) is species
    assert prefer_content({"edibility": "unknown", "toxicity": "unknown", "level": "quick"}, edible) is edible


def _pack(path: Path, rows: list[dict]) -> None:
    con = sqlite3.connect(path)
    con.executescript(
        """
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE species (
          id INTEGER PRIMARY KEY, inat_taxon_id INTEGER, gbif_key INTEGER,
          scientific_name TEXT, common_name TEXT, common_names TEXT,
          kingdom TEXT, family TEXT, genus TEXT, obs_count INTEGER, months BLOB,
          description TEXT, wiki_url TEXT);
        CREATE TABLE photo (
          id INTEGER PRIMARY KEY, species_id INTEGER, rank INTEGER, thumb BLOB, image BLOB,
          observer TEXT, license TEXT, url TEXT, stage TEXT);
        CREATE TABLE prototype (
          id INTEGER PRIMARY KEY, species_id INTEGER, vec BLOB, scale REAL, kind TEXT);
        CREATE TABLE content (species_id INTEGER PRIMARY KEY, json TEXT);
        CREATE TABLE lookalike (
          species_id INTEGER, lookalike_species_id INTEGER, lookalike_name TEXT, danger TEXT);
        CREATE TABLE attribution (
          id INTEGER PRIMARY KEY, species_id INTEGER, observer TEXT, license TEXT, url TEXT);
        """
    )
    con.execute("INSERT INTO meta VALUES ('pack_version', '1')")
    con.execute("INSERT INTO meta VALUES ('built_at', '2020-01-01T00:00:00Z')")
    con.execute("INSERT INTO meta VALUES ('region_id', 'ID')")
    for row in rows:
        con.execute(
            "INSERT INTO species VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (row["id"], None, None, row["name"], row.get("common"), None, "Plantae", None, row["name"].split()[0],
             1, None, row.get("description"), None),
        )
        con.execute("INSERT INTO content VALUES (?, ?)", (row["id"], json.dumps(row["content"])))
        for n, photo in enumerate(row.get("photos") or []):
            con.execute(
                "INSERT INTO photo (species_id, rank, thumb, image, observer, license, url, stage) VALUES (?,?,?,?,?,?,?,?)",
                (row["id"], n, b"t", photo["image"], "Ann", "cc-by", photo["url"], photo.get("stage") or "field"),
            )
        for proto in row.get("protos") or []:
            con.execute(
                "INSERT INTO prototype (species_id, vec, scale, kind) VALUES (?,?,?,'image')",
                (row["id"], proto, 1.0),
            )
    con.commit()
    con.close()


def test_merge_adds_finer_species_and_skips_a_hidden_url(tmp_path: Path):
    parent = tmp_path / "ID.owpack"
    child = tmp_path / "ID-BA.owpack"
    out = tmp_path / "merged.owpack"
    _pack(parent, [{
        "id": 1, "name": "Nerium oleander", "content": {"toxicity": "none", "edibility": "edible", "level": "quick"},
        "photos": [{"url": "http://example.test/a.jpg", "image": b"aaa"}],
        "protos": [b"p1"],
    }])
    _pack(child, [
        {
            "id": 7, "name": "Nerium oleander",
            "content": {"toxicity": "deadly", "edibility": "deadly", "level": "species"},
            "photos": [
                {"url": "http://example.test/a.jpg", "image": b"aaa"},
                {"url": "http://example.test/hidden.jpg", "image": b"bad"},
                {"url": "http://example.test/b.jpg", "image": b"bbb"},
            ],
            "protos": [b"p1", b"p2"],
        },
        {
            "id": 8, "name": "Cerbera odollam",
            "content": {"toxicity": "deadly", "edibility": "deadly", "level": "species"},
            "photos": [{"url": "http://example.test/c.jpg", "image": b"ccc", "stage": "flower"}],
            "protos": [b"p3"],
        },
    ])
    stats = merge_packs(parent, child, out, hide={"http://example.test/hidden.jpg"})
    assert stats["added_species"] == 1
    assert stats["safety_rows_updated"] == 1
    con = sqlite3.connect(out)
    names = {n for n, in con.execute("SELECT scientific_name FROM species")}
    assert names == {"Nerium oleander", "Cerbera odollam"}
    urls = {u for u, in con.execute("SELECT url FROM photo")}
    assert "http://example.test/hidden.jpg" not in urls
    assert urls == {"http://example.test/a.jpg", "http://example.test/b.jpg", "http://example.test/c.jpg"}
    ole = json.loads(con.execute(
        "SELECT json FROM content c JOIN species s ON s.id=c.species_id WHERE s.scientific_name='Nerium oleander'"
    ).fetchone()[0])
    assert ole["toxicity"] == "deadly"
    # The shared fingerprint is stored once; the finer pack's extra fingerprint is kept.
    assert con.execute("SELECT COUNT(*) FROM prototype").fetchone()[0] == 3
    con.close()


def test_example_caption_names_the_depicted_species():
    from openwild_pipeline.danger_photos import display_observer

    assert display_observer({
        "observer": "Ada Lovelace", "stage": "example", "depicts": "Cycas rumphii",
    }) == "Ada Lovelace — example: Cycas rumphii"
    assert display_observer({"observer": "Ada Lovelace", "stage": "field", "depicts": ""}) == "Ada Lovelace"


def test_example_photo_is_shown_and_not_fingerprinted(tmp_path: Path):
    import numpy as np

    pack = tmp_path / "p.owpack"
    _pack(pack, [{
        "id": 1, "name": "Cycas montana",
        "content": {"toxicity": "deadly", "edibility": "unknown", "level": "genus"},
    }])
    img = tmp_path / "leaf.jpg"
    Image.new("RGB", (400, 300), (20, 120, 40)).save(img, "JPEG")
    emb = np.ones((2, 8), np.float32)
    result = add_display_photos(pack, [
        {
            "scientific_name": "Cycas montana",
            "file": str(img),
            "license": "cc-by-sa",
            "observer": "Ada Lovelace — example: Cycas rumphii",
            "page": "https://commons.wikimedia.org/wiki/File:Cycas_rumphii.jpg",
            "stage": "example",
        },
        {
            "scientific_name": "Cycas montana",
            "file": str(img),
            "license": "cc-by",
            "observer": "Ada Lovelace",
            "page": "https://commons.wikimedia.org/wiki/File:Cycas_montana.jpg",
            "stage": "leaves",
        },
    ], embeddings=emb)
    assert result["photos_added"] == 2
    assert result["prototypes_added"] == 1
    con = sqlite3.connect(pack)
    stages = {r[0] for r in con.execute("SELECT stage FROM photo")}
    assert stages == {"example", "leaves"}
    assert con.execute("SELECT COUNT(*) FROM prototype").fetchone()[0] == 1
    con.close()


def test_two_photos_from_one_page_both_ship(tmp_path: Path):
    pack = tmp_path / "p.owpack"
    _pack(pack, [{
        "id": 1, "name": "Amanita borneensis",
        "content": {"toxicity": "deadly", "edibility": "unknown", "level": "genus"},
    }])
    img = tmp_path / "cap.jpg"
    Image.new("RGB", (400, 300), (40, 30, 20)).save(img, "JPEG")
    page = "https://www.inaturalist.org/observations/25991355"
    result = add_display_photos(pack, [
        {"scientific_name": "Amanita borneensis", "file": str(img), "license": "cc-by-sa",
         "observer": "Chael Thomas — example: Amanita fuliginea", "page": page,
         "image_url": "https://example.test/a.jpg", "stage": "example"},
        {"scientific_name": "Amanita borneensis", "file": str(img), "license": "cc-by-sa",
         "observer": "Chael Thomas — example: Amanita fuliginea", "page": page,
         "image_url": "https://example.test/b.jpg", "stage": "example"},
    ], embeddings=None)
    assert result["photos_added"] == 2


def test_added_photo_becomes_the_cover(tmp_path: Path):
    pack = tmp_path / "p.owpack"
    _pack(pack, [{
        "id": 1, "name": "Cycas montana",
        "content": {"toxicity": "deadly", "edibility": "unknown", "level": "genus"},
        "photos": [{"url": "http://example.test/related.jpg", "image": b"rel", "stage": "related"}],
    }])
    img = tmp_path / "leaf.jpg"
    Image.new("RGB", (400, 300), (20, 120, 40)).save(img, "JPEG")
    result = add_display_photos(pack, [{
        "scientific_name": "Cycas montana",
        "file": str(img),
        "license": "cc-by",
        "observer": "Ada Lovelace",
        "page": "https://commons.wikimedia.org/wiki/File:Cycas_montana.jpg",
        "stage": "leaves",
    }], embeddings=None)
    assert result["photos_added"] == 1
    con = sqlite3.connect(pack)
    cover = con.execute(
        "SELECT stage, observer, license FROM photo WHERE species_id=1 ORDER BY rank LIMIT 1"
    ).fetchone()
    assert cover[0] == "leaves"
    assert cover[1] == "Ada Lovelace"
    assert cover[2] == "cc-by"
    con.close()
