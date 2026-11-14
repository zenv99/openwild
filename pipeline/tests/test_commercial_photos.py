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


def test_inaturalist_urls_follow_policy(monkeypatch):
    from openwild_pipeline import config as _c

    # Free, non-commercial app: iNaturalist photos are used under their own CC licences.
    assert not is_inaturalist("https://inaturalist-open-data.s3.amazonaws.com/photos/1/original.jpg")
    monkeypatch.setattr(_c, "ALLOW_INATURALIST", False)
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
    # Free, non-commercial app: CC BY-NC is allowed (credited). Identical image bytes are stored once.
    assert ("cc-by-nc", "flower", "Ann") in photos
    assert ("cc-by-sa", "flower", "Bo") not in photos  # same file as Ann's
    assert {row[0] for row in photos} <= {"cc0", "cc-by", "cc-by-sa", "cc-by-nc", "cc-by-nc-sa"}
    assert con.execute("SELECT COUNT(*) FROM prototype").fetchone()[0] == 2


def test_commercial_mode_still_refuses_nc_and_nd(monkeypatch):
    from openwild_pipeline import config

    assert config.photo_license("CC BY-ND 4.0") is None
    monkeypatch.setattr(config, "PHOTO_LICENSES", config.COMMERCIAL_LICENSES)
    assert config.photo_license("CC BY-NC 4.0") is None
    assert config.photo_license("CC BY-SA 3.0") == "cc-by-sa"


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
    # iNaturalist and CC BY-NC are allowed for the free app; order follows GBIF's.
    assert [c["url"] for c in out] == ["https://inat/x.jpg", "https://n/a.jpg", "https://p/b.jpg", "https://o/c.jpg"]
    assert {c["license"] for c in out} == {"cc0", "cc-by", "cc-by-sa", "cc-by-nc"}


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


def test_three_per_stage_round_robin(tmp_path, monkeypatch):
    from openwild_pipeline import commercial_photos as cp

    def fake_download(url, dest):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(url.encode() * 200)
        return True

    monkeypatch.setattr(cp, "_download", fake_download)
    cands = [{"url": f"https://x/{st}{i}", "stage": st, "license": "cc-by", "observer": "", "page": "", "source": "gbif"}
             for st in ("flower", "fruit", "leaves", "field") for i in range(5)]
    got = cp._take(cands, tmp_path, "Musa acuminata")
    stages = [g["stage"] for g in got]
    assert stages[:4] == ["leaves", "flower", "fruit", "field"]          # first photos show different stages
    assert all(stages.count(s) == 3 for s in ("flower", "fruit", "leaves", "field"))


def test_no_evidence_of_flowering_is_leaves():
    from openwild_pipeline.commercial_photos import stage_from_text

    assert stage_from_text("no evidence of flowering") == "leaves"
    assert stage_from_text("flowering") == "flower"
    assert stage_from_text("fruiting") == "fruit"


def test_inaturalist_source_stages_and_licences(monkeypatch):
    from openwild_pipeline import commercial_photos as cp

    data = {"results": [
        {"id": 1, "taxon": {"name": "Plumeria rubra"}, "user": {"login": "made"},
         "annotations": [{"controlled_attribute_id": 12, "controlled_value_id": 13}],
         "photos": [{"url": "https://inaturalist-open-data.s3.amazonaws.com/photos/1/square.jpg", "license_code": "cc-by-nc"},
                    {"url": "https://x/2/square.jpg", "license_code": None}]},
        {"id": 2, "taxon": {"name": "Plumeria"}, "photos": [{"url": "https://x/3/square.jpg", "license_code": "cc0"}]},
    ]}
    monkeypatch.setattr(cp, "get_json", lambda *a, **k: data)
    out = cp.from_inaturalist("Plumeria rubra")
    assert len(out) == 1  # all-rights-reserved photo and parent-taxon observation skipped
    assert out[0]["stage"] == "flower" and out[0]["license"] == "cc-by-nc" and out[0]["url"].endswith("/medium.jpg")
