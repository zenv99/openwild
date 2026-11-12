"""Guards against a broken CLI module (every workflow runs through it)."""
import subprocess
import sys


def test_cli_lists_commands():
    out = subprocess.run([sys.executable, "-m", "openwild_pipeline.cli", "--help"], capture_output=True, text=True)
    assert out.returncode == 0, out.stderr
    for cmd in ("photos", "photo-pack", "photo-batch", "photo-baseline", "pack-index", "kew", "buckets", "wikidata", "summary"):
        assert cmd in out.stdout


def test_cli_module_is_sane():
    from pathlib import Path

    src = Path(__file__).resolve().parents[1] / "openwild_pipeline" / "cli.py"
    assert src.stat().st_size < 200_000


def test_photo_pack_command_end_to_end(tmp_path, monkeypatch):
    """Runs `photo-pack` with fake fingerprints: catches broken code paths before a 2-hour CI build does."""
    import argparse
    import csv
    import sqlite3

    import numpy as np
    from PIL import Image

    from openwild_pipeline import cli, config, freemodel

    work, data = tmp_path / "work", tmp_path / "data"
    pdir = work / "photos_gbif" / "ID-BA"
    pdir.mkdir(parents=True)
    data.mkdir()
    rows = []
    for sp in ("Musa acuminata", "Cocos nucifera"):
        for k in range(4):
            img = pdir / f"{sp[:4]}{k}.jpg"
            Image.new("RGB", (64, 48), (k * 40, 120, 60)).save(img)
            rows.append({"scientific_name": sp, "path": str(img), "license": "cc-by", "observer": f"o{k}",
                         "page": f"https://x/{sp}/{k}", "kind": "field" if k else "specimen"})
    with open(pdir / "photos.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    with open(data / "species.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "kingdom", "gbif_key", "family", "genus", "gbif_count"])
        w.writeheader()
        w.writerow({"scientific_name": "Musa acuminata", "kingdom": "Plantae", "gbif_key": "1", "family": "Musaceae", "genus": "Musa", "gbif_count": "9"})
        w.writerow({"scientific_name": "Cocos nucifera", "kingdom": "Plantae", "gbif_key": "2", "family": "Arecaceae", "genus": "Cocos", "gbif_count": "5"})

    rng = np.random.default_rng(0)
    monkeypatch.setattr(config, "WORK", work)
    monkeypatch.setattr(cli, "data_dir", lambda region: data)
    monkeypatch.setattr(freemodel, "embed_paths", lambda paths, **_: rng.normal(size=(len(paths), config.EMBED_DIM)).astype(np.float32))
    out = tmp_path / "ID-BA.owpack"
    cli.cmd_photo_pack(argparse.Namespace(region="ID-BA", version=5, out=str(out), photos_shown=3))
    con = sqlite3.connect(out)
    assert con.execute("SELECT COUNT(DISTINCT species_id) FROM photo").fetchone()[0] == 2
    first = con.execute("SELECT observer FROM photo WHERE rank = 0").fetchall()
    assert all(o[0] != "o0" for o in first)  # herbarium sheet never the cover


def test_photo_fetch_caps_and_prefers_field(monkeypatch, tmp_path):
    import csv

    from openwild_pipeline import gbif_photos as g

    def fake_get(url, params=None, **_):
        res = []
        for k in range(100):
            res.append({"key": k, "basisOfRecord": "PRESERVED_SPECIMEN" if k < 50 else "HUMAN_OBSERVATION",
                        "media": [{"identifier": f"https://img/{params['offset'] + k}_{j}.jpg", "license": "http://creativecommons.org/licenses/by/4.0/",
                                   "type": "StillImage"} for j in range(3)]})
        return {"results": res, "endOfRecords": False}

    monkeypatch.setattr(g, "get_json", fake_get)
    monkeypatch.setattr(g, "_download", lambda url, dest: "_1." not in url)  # every second photo fails
    sp = tmp_path / "species.csv"
    with open(sp, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "gbif_key", "gbif_count"])
        w.writeheader()
        w.writerow({"scientific_name": "Musa acuminata", "gbif_key": "7", "gbif_count": "9"})
    out = g.fetch_region_photos(sp, tmp_path / "o", per_species=8, workers=1)
    rows = list(csv.DictReader(open(out)))
    assert len(rows) == 8 and all(r["kind"] == "field" for r in rows)
