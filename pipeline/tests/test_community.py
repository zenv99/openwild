import csv
import io

from PIL import Image

from openwild_pipeline import community, config

BODY = """**Species:** Plumeria rubra
**Region:** ID-BA
**Credit:** Made Wira
**Licence:** CC BY 4.0
**Stages:** closeup x1, flower x1

![Plumeria_rubra__closeup__1](https://github.com/user-attachments/assets/aaa)
<img width="300" alt="Plumeria_rubra__flower__2" src="https://github.com/user-attachments/assets/bbb" />
![evil](https://evil.example.com/x.jpg)
"""


def _jpeg_with_gps() -> bytes:
    im = Image.new("RGB", (40, 30), (10, 200, 30))
    exif = Image.Exif()
    exif[0x8825] = {2: (1.0, 2.0, 3.0)}  # GPSInfo
    out = io.BytesIO()
    im.save(out, "JPEG", exif=exif)
    return out.getvalue()


def test_parse_issue_fields_and_stages():
    info = community.parse_issue(BODY)
    assert info["species"] == "Plumeria rubra" and info["region"] == "ID-BA" and info["credit"] == "Made Wira"
    assert info["images"] == [("closeup", "https://github.com/user-attachments/assets/aaa"),
                              ("flower", "https://github.com/user-attachments/assets/bbb")]  # foreign host dropped


def test_licence_and_credit_rules():
    assert community.licence_ok("CC BY 4.0") and community.licence_ok("cc0")
    assert not community.licence_ok("CC BY-NC 4.0") and not community.licence_ok("")
    assert "<" not in community.clean_credit("<b>[Zen](x)</b>") and "Zen" in community.clean_credit("[Zen]")


def test_ingest_strips_metadata_and_puts_credit(tmp_path, monkeypatch):
    monkeypatch.setattr(community, "COMMUNITY", tmp_path / "community")
    monkeypatch.setattr(config, "REPO_ROOT", tmp_path)
    issue = {"number": 7, "body": BODY, "html_url": "https://github.com/zenv99/openwild/issues/7"}
    res = community.ingest_issues([issue], lambda url: _jpeg_with_gps(), {"ID-BA": {"plumeria rubra"}})
    assert len(res["added"]) == 2 and not res["rejected"]
    row = res["added"][0]
    assert row["observer"] == "Made Wira (OpenWild community)" and row["license"] == "cc-by"
    data = (tmp_path / row["path"]).read_bytes()
    assert b"Exif" not in data[:200]
    assert not Image.open(io.BytesIO(data)).getexif()
    rows = list(csv.DictReader(open(tmp_path / "community" / "ID-BA" / "community.csv")))
    assert {r["stage"] for r in rows} == {"closeup", "flower"}
    # Same issue again: nothing duplicated.
    again = community.ingest_issues([issue], lambda url: _jpeg_with_gps(), {"ID-BA": {"plumeria rubra"}})
    assert again["rejected"] == {7: "no readable photos"}


def test_ingest_rejects_wrong_licence_or_species():
    nc = BODY.replace("CC BY 4.0", "CC BY-NC 4.0")
    r = community.ingest_issues([{"number": 1, "body": nc}], lambda u: b"", {"ID-BA": {"plumeria rubra"}})
    assert "CC BY 4.0" in r["rejected"][1]
    r = community.ingest_issues([{"number": 2, "body": BODY}], lambda u: b"", {"ID-BA": {"cocos nucifera"}})
    assert "not in region" in r["rejected"][2]


FORM = """### Species

Plumeria rubra

### Region

ID-BA

### Credit

Made Wira

### Stages

closeup x1

### Licence

- [X] I took these photos myself and release them under CC BY 4.0, credited to the name above.

### Photos

![Plumeria_rubra__closeup__1](https://github.com/user-attachments/assets/ccc)
"""


def test_parse_issue_form():
    info = community.parse_issue(FORM)
    assert (info["species"], info["region"], info["credit"]) == ("Plumeria rubra", "ID-BA", "Made Wira")
    assert community.licence_ok(info["license"])
    assert info["images"] == [("closeup", "https://github.com/user-attachments/assets/ccc")]
    unticked = community.parse_issue(FORM.replace("- [X]", "- [ ]"))
    assert not community.licence_ok(unticked["license"])
