"""Commercial-ok photo rules: licence gate, life-stage labels, and what a pack may store."""
import sqlite3

from PIL import Image

from openwild_pipeline.commercial_photos import is_inaturalist, stage_from_category, stage_from_text
from openwild_pipeline.config import commercial_license
from openwild_pipeline.pack import SpeciesRecord, build_pack, encode_photo


def test_stage_labels_come_from_words_not_guesses():
    assert stage_from_text("Cocos nucifera (seedlings)") == "seedling"
    assert stage_from_text("seeds of coconut") == "fruit"
    assert stage_from_text("mature leaves") == "leaves"
    assert stage_from_text("flowering branch") == "flower"
    assert stage_from_text("bark close-up") == "closeup"
    assert stage_from_text("a nice picture") is None
    assert stage_from_category("Category:Coconuts", "Cocos nucifera") is None
    assert stage_from_category("Category:Cocos nucifera (flowers)", "Cocos nucifera") == "flower"
    assert stage_from_category("Category:Diseases and disorders of Cocos nucifera", "Cocos nucifera") is None


def test_commercial_licences_reject_nc_and_unknown():
    assert commercial_license("CC BY-SA 4.0") == "cc-by-sa"
    assert commercial_license("CC0") == "cc0"
    assert commercial_license("http://creativecommons.org/licenses/by/4.0/") == "cc-by"
    assert commercial_license("http://creativecommons.org/licenses/by-nc/4.0/") is None
    assert commercial_license("CC BY-NC-SA 4.0") is None
    assert commercial_license("CC BY-ND 4.0") is None
    assert commercial_license("") is None
    assert commercial_license(None) is None


def test_inaturalist_urls_are_refused():
    assert is_inaturalist("https://inaturalist-open-data.s3.amazonaws.com/photos/1/original.jpg")
    assert is_inaturalist(None, "https://www.inaturalist.org/photos/1")
    assert not is_inaturalist("https://commons.wikimedia.org/wiki/File:Coconut.jpg")


def test_pack_stores_only_commercial_webp_and_keeps_each_stage(tmp_path):
    img = tmp_path / "a.jpg"
    Image.new("RGB", (800, 600), (20, 140, 40)).save(img, quality=95)
    noisy = tmp_path / "b.jpg"
    import numpy as np
    from PIL import Image as Im
    grad = np.zeros((700, 500, 3), np.uint8)
    grad[:, :, 1] = np.linspace(30, 160, 500, dtype=np.uint8)
    Im.fromarray(grad, "RGB").save(noisy, quality=90)
    enc = encode_photo(noisy)
    assert enc is not None
    from io import BytesIO
    webp = Image.open(BytesIO(enc[1]))
    assert webp.format == "WEBP"
    assert max(webp.size) <= 320
    assert len(enc[1]) <= 25_000

    recs = [SpeciesRecord(
        scientific_name="Cocos nucifera", kingdom="Plantae",
        embeddings=np.stack([np.ones(4, dtype=np.float32), np.arange(4, dtype=np.float32) + 1]),
        photos=[
            {"path": str(img), "observer": "Ann", "license": "cc-by-nc", "url": "https://x/nc", "stage": "flower"},
            {"path": str(img), "observer": "Bo", "license": "cc-by-sa", "url": "https://commons.wikimedia.org/wiki/File:A", "stage": "flower"},
            {"path": str(noisy), "observer": "Cy", "license": "https://creativecommons.org/licenses/by/4.0/", "url": "https://commons.wikimedia.org/wiki/File:B", "stage": "fruit"},
            {"path": str(img), "observer": "Inat", "license": "cc-by", "url": "https://www.inaturalist.org/photos/9", "stage": "leaves"},
        ],
    )]
    out = build_pack(
        tmp_path / "P.owpack", "ID-BA", "Bali", 3, recs, {}, guide_only=True,
        min_photos_for_image_protos=1, photos_per_species=5, keep_each_prototype=True,
    )
    con = sqlite3.connect(out)
    photos = con.execute("SELECT license, stage, observer FROM photo ORDER BY rank").fetchall()
    # iNaturalist filtering is the fetcher's job; the pack still refuses NC.
    assert ("cc-by-nc", "flower", "Ann") not in photos
    assert {row[0] for row in photos} <= {"cc0", "cc-by", "cc-by-sa"}
    assert ("cc-by-sa", "flower", "Bo") in photos
    assert con.execute("SELECT COUNT(*) FROM prototype").fetchone()[0] == 2


def test_gbif_licence_filter_and_no_inaturalist(monkeypatch):
    from openwild_pipeline import commercial_photos as cp

    seen = {}

    def fake(url, params=None, **_):
        seen.update(params)
        return {"endOfRecords": True, "results": [
            {"key": 1, "datasetKey": cp.INAT_DATASET, "media": [{"identifier": "https://inat/x.jpg", "license": "CC BY 4.0"}]},
            {"key": 2, "datasetKey": "naturalis", "basisOfRecord": "PRESERVED_SPECIMEN",
             "media": [{"identifier": "https://n/a.jpg", "license": "http://creativecommons.org/publicdomain/zero/1.0/"}]},
            {"key": 3, "datasetKey": "plantnet", "basisOfRecord": "HUMAN_OBSERVATION",
             "media": [{"identifier": "https://p/b.jpg", "license": "CC BY-SA 4.0"}]},
            {"key": 4, "datasetKey": "other", "media": [{"identifier": "https://o/c.jpg", "license": "CC BY-NC 4.0"}]},
        ]}

    monkeypatch.setattr(cp, "get_json", fake)
    out = cp.from_gbif(123)
    assert seen["license"] == list(cp.GBIF_COMMERCIAL)
    assert [c["url"] for c in out] == ["https://n/a.jpg", "https://p/b.jpg"]
    assert {c["license"] for c in out} == {"cc0", "cc-by-sa"}


def test_licence_versions_ports_and_public_domain():
    from openwild_pipeline import config

    assert config.commercial_license("CC BY-SA 2.5 nl") == "cc-by-sa"
    assert config.commercial_license("CC BY 2.5") == "cc-by"
    assert config.commercial_license("PD-old") == "cc0"
    assert config.commercial_license("CC BY-NC-SA 2.0") is None
    assert config.commercial_license("CC BY-ND 4.0") is None


def test_http_encodes_repeated_params():
    import urllib.parse

    q = urllib.parse.urlencode({"license": ["CC0_1_0", "CC_BY_4_0"], "limit": "100"}, doseq=True)
    assert q == "license=CC0_1_0&license=CC_BY_4_0&limit=100"
