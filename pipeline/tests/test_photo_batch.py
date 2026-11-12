"""Priority and licence rules for the weekly photo backfill. No network."""
import csv
import sqlite3

from openwild_pipeline.photo_batch import (
    _alt_inat_url,
    _commons_from_imageinfo,
    baseline_from_pack,
    fetch_recorded,
    inat_medium_url,
    priority_key,
    select_batch,
)


def _row(name, count="0", genus=""):
    return {"scientific_name": name, "gbif_count": count, "genus": genus or name.split()[0]}


def test_priority_puts_deadly_and_food_ahead_of_the_tail():
    curated = {
        "nerium oleander": {"toxicity": "deadly", "edibility": "deadly", "survival": {"priority": "avoid"}},
        "psidium guajava": {"toxicity": "none", "edibility": "edible", "survival": {"priority": "high_value"}},
    }
    quick = {"datura metel": {"verdict": "deadly"}, "lantana camara": {"verdict": "toxic"},
             "mangifera indica": {"verdict": "edible"}}
    rules = {("genus", "amanita"): {"toxicity": "deadly"}}
    looks = {"nerium oleander"}
    names = ["Some weed", "Amanita muscaria", "Mangifera indica", "Lantana camara", "Datura metel",
             "Psidium guajava", "Nerium oleander"]
    rows = [_row(n, count=str(i)) for i, n in enumerate(names)]
    order = [r["scientific_name"] for r in sorted(rows, key=lambda r: priority_key(r, curated, quick, rules, looks))]
    assert order[:2] == ["Nerium oleander", "Datura metel"]
    assert order.index("Psidium guajava") < order.index("Lantana camara") < order.index("Mangifera indica")
    assert order.index("Amanita muscaria") < order.index("Some weed")


def test_select_batch_skips_finished_species_and_obeys_tier_cap():
    curated = {"nerium oleander": {"toxicity": "deadly", "edibility": "deadly"}}
    quick = {"datura metel": {"verdict": "deadly"}, "lantana camara": {"verdict": "toxic"}}
    rows = [_row("Nerium oleander"), _row("Datura metel"), _row("Lantana camara"), _row("Nerium oleander")]
    status = {"nerium oleander": {"state": "have"}}
    got = select_batch(rows, status, limit=10, max_tier=0, curated=curated, quick=quick, rules={}, lookalikes=set())
    assert [r["scientific_name"] for r in got] == ["Datura metel"]


def test_inat_medium_url_and_extension_fallback():
    assert inat_medium_url("https://inaturalist-open-data.s3.amazonaws.com/photos/9/square.jpeg").endswith("/medium.jpeg")
    jpg = "https://inaturalist-open-data.s3.amazonaws.com/photos/9/medium.jpg"
    assert _alt_inat_url(jpg).endswith("/medium.jpeg")
    assert _alt_inat_url("https://upload.wikimedia.org/a.jpg") is None


def test_commons_keeps_cc_field_photo_and_drops_nd_and_all_rights():
    pages = {
        "1": {"title": "File:Flower.jpg", "imageinfo": [{
            "thumburl": "https://upload.wikimedia.org/flower.jpg",
            "descriptionurl": "https://commons.wikimedia.org/wiki/File:Flower.jpg",
            "mime": "image/jpeg", "user": "Ada",
            "extmetadata": {"LicenseShortName": {"value": "CC BY-SA 4.0"},
                            "Artist": {"value": "<b>Ada Lovelace</b>"},
                            "ImageDescription": {"value": "in habitat"}},
        }]},
        "2": {"title": "File:Sheet.jpg", "imageinfo": [{
            "thumburl": "https://upload.wikimedia.org/sheet.jpg",
            "descriptionurl": "https://commons.wikimedia.org/wiki/File:Sheet.jpg",
            "mime": "image/jpeg",
            "extmetadata": {"LicenseShortName": {"value": "CC BY 4.0"},
                            "ImageDescription": {"value": "herbarium sheet"}},
        }]},
        "3": {"title": "File:NoDerivs.jpg", "imageinfo": [{
            "thumburl": "https://upload.wikimedia.org/nd.jpg", "mime": "image/jpeg",
            "extmetadata": {"LicenseShortName": {"value": "CC BY-NC-ND 4.0"}},
        }]},
        "4": {"title": "File:Copyright.jpg", "imageinfo": [{
            "thumburl": "https://upload.wikimedia.org/c.jpg", "mime": "image/jpeg",
            "extmetadata": {"LicenseShortName": {"value": "All rights reserved"}},
        }]},
    }
    got = _commons_from_imageinfo("Nerium oleander", pages)
    assert [r["image_url"] for r in got] == [
        "https://upload.wikimedia.org/flower.jpg",
        "https://upload.wikimedia.org/sheet.jpg",
    ]
    assert got[0]["kind"] == "field" and got[0]["license"] == "cc-by-sa" and got[0]["observer"] == "Ada Lovelace"
    assert got[1]["kind"] == "specimen"


def test_occurrence_still_image_skips_manifests_and_nd():
    from openwild_pipeline.photo_batch import _still_image

    occ = {
        "basisOfRecord": "PRESERVED_SPECIMEN",
        "license": "http://creativecommons.org/publicdomain/zero/1.0/",
        "media": [
            {"type": "InteractiveResource", "identifier": "https://example.test/manifest",
             "license": "http://creativecommons.org/publicdomain/zero/1.0/"},
            {"type": "StillImage", "identifier": "https://example.test/full/1600,/0/default.jpg",
             "license": "http://creativecommons.org/licenses/by-nc-nd/4.0/", "creator": "ND Person"},
            {"type": "StillImage", "identifier": "https://example.test/full/1600,/0/default.jpg",
             "license": "http://creativecommons.org/publicdomain/zero/1.0/", "rightsHolder": "Edinburgh"},
        ],
    }
    # ND is refused; the CC0 JPEG is kept and marked as a specimen.
    url, lic, observer, kind = _still_image(occ)
    assert lic == "cc0" and observer == "Edinburgh" and kind == "specimen" and url.endswith(".jpg")


def test_baseline_pins_inat_urls_and_leaves_other_credits_for_refetch(tmp_path):
    pack = tmp_path / "ID-BA.owpack"
    con = sqlite3.connect(pack)
    con.execute("CREATE TABLE meta (key TEXT, value TEXT)")
    con.execute("INSERT INTO meta VALUES ('pack_version', '2026111202')")
    con.execute("CREATE TABLE species (id INTEGER, scientific_name TEXT)")
    con.execute("CREATE TABLE photo (species_id INTEGER, rank INTEGER, url TEXT, license TEXT, observer TEXT)")
    con.executemany("INSERT INTO species VALUES (?, ?)", [(1, "Nerium oleander"), (2, "Rare moss")])
    con.execute("INSERT INTO photo VALUES (1, 0, 'https://www.inaturalist.org/photos/42', 'cc-by-nc', 'A. Photographer')")
    con.execute("INSERT INTO photo VALUES (2, 0, 'https://www.gbif.org/occurrence/99', 'cc-by', 'Herbarium')")
    con.commit()
    con.close()
    stats = baseline_from_pack(pack, tmp_path / "photo_manifest.csv", tmp_path / "photo_status.csv")
    assert stats["pinned_species"] == 1 and stats["refetch_on_publish"] == 1
    manifest = list(csv.DictReader(open(tmp_path / "photo_manifest.csv", encoding="utf-8")))
    assert manifest[0]["image_url"].endswith("/photos/42/medium.jpg")
    assert manifest[0]["license"] == "cc-by-nc" and manifest[0]["observer"] == "A. Photographer"
    assert manifest[0]["source"] == "pack:2026111202"
    status = {r["scientific_name"]: r["state"] for r in csv.DictReader(open(tmp_path / "photo_status.csv", encoding="utf-8"))}
    assert status == {"Nerium oleander": "have", "Rare moss": "have"}


def test_fetch_recorded_does_not_crawl_pending_species(tmp_path, monkeypatch):
    from openwild_pipeline import gbif_photos

    species = tmp_path / "species.csv"
    with open(species, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "gbif_key", "gbif_count"])
        w.writeheader()
        w.writerow({"scientific_name": "Nerium oleander", "gbif_key": "1", "gbif_count": "10"})
        w.writerow({"scientific_name": "Rare moss", "gbif_key": "2", "gbif_count": "1"})
        w.writerow({"scientific_name": "Still missing", "gbif_key": "3", "gbif_count": "4"})
    manifest = tmp_path / "photo_manifest.csv"
    with open(manifest, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "image_url", "license", "observer", "page", "kind", "source", "batch"])
        w.writeheader()
        w.writerow({"scientific_name": "Nerium oleander", "image_url": "https://example.test/n.jpg",
                    "license": "cc-by", "observer": "Ada", "page": "https://example.test/n",
                    "kind": "field", "source": "pack:1", "batch": "1"})
    status = tmp_path / "photo_status.csv"
    with open(status, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "state", "batch", "note"])
        w.writeheader()
        w.writerow({"scientific_name": "Nerium oleander", "state": "have", "batch": "1", "note": ""})
        w.writerow({"scientific_name": "Rare moss", "state": "have", "batch": "1", "note": "refetch"})
        w.writerow({"scientific_name": "Still missing", "state": "error", "batch": "1", "note": "later"})

    seen = {}

    def fake_download(url, dest):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(b"jpeg")
        return True

    def fake_fetch(species_csv, out_dir, per_species=8, workers=16, max_species=None):
        names = [r["scientific_name"] for r in csv.DictReader(open(species_csv, encoding="utf-8"))]
        seen["names"] = names
        out_dir.mkdir(parents=True, exist_ok=True)
        with open(out_dir / "photos.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["scientific_name", "path", "license", "observer", "page", "kind"])
            w.writeheader()
            w.writerow({"scientific_name": "Rare moss", "path": "/tmp/m.jpg", "license": "cc0",
                        "observer": "H", "page": "https://www.gbif.org/occurrence/1", "kind": "specimen"})

    monkeypatch.setattr(gbif_photos, "_download", fake_download)
    monkeypatch.setattr("openwild_pipeline.photo_batch._download", fake_download)
    monkeypatch.setattr(gbif_photos, "fetch_region_photos", fake_fetch)
    fetch_recorded(species, tmp_path / "out", manifest, status, per_species=2, workers=16)
    assert seen["names"] == ["Rare moss"]
    got = list(csv.DictReader(open(tmp_path / "out" / "photos.csv", encoding="utf-8")))
    assert {r["scientific_name"] for r in got} == {"Nerium oleander", "Rare moss"}
    assert got[0]["observer"] == "Ada" and got[0]["license"] == "cc-by"
