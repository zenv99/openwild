import csv
import zipfile

from openwild_pipeline import config, opendata
from openwild_pipeline.opendata import _verdict, merge_wcvp_into_species_csv


def test_verdict_mapping_is_conservative():
    assert _verdict("deadly mushroom") == "deadly"
    assert _verdict("poisonous mushroom") == "toxic"
    assert _verdict("psychoactive mushroom") == "psychoactive"
    assert _verdict("edible with caution") == "inedible"
    assert _verdict("choice edible mushroom") == "edible"
    assert _verdict("edible when cooked") == "edible_cooked"


def test_wcvp_parse_and_merge(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WORK", tmp_path)
    with zipfile.ZipFile(tmp_path / "wcvp.zip", "w") as z:
        z.writestr("wcvp_names.csv", "plant_name_id|taxon_rank|taxon_status|family|genus|taxon_name\n"
                   "1|Species|Accepted|Fabaceae|Abrus|Abrus precatorius\n"
                   "2|Species|Synonym|Fabaceae|Abrus|Abrus oldname\n"
                   "3|Species|Accepted|Arecaceae|Cocos|Cocos nucifera\n"
                   "4|Species|Accepted|Pinaceae|Pinus|Pinus sylvestris\n")
        z.writestr("wcvp_distribution.csv", "plant_locality_id|plant_name_id|area_code_l3|introduced|extinct\n"
                   "a|1|LSI|0|0\nb|2|LSI|0|0\nc|3|LSI|1|0\nd|4|GRB|0|0\n")
    rows = opendata.wcvp_species(["LSI"])
    assert sorted(r["scientific_name"] for r in rows) == ["Abrus precatorius", "Cocos nucifera"]
    sp = tmp_path / "species.csv"
    with open(sp, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["scientific_name", "kingdom", "family", "genus", "gbif_count"])
        w.writeheader()
        w.writerow({"scientific_name": "Cocos nucifera", "kingdom": "Plantae", "gbif_count": 100})
    assert merge_wcvp_into_species_csv(sp, rows) == 1
    names = [r["scientific_name"] for r in csv.DictReader(open(sp))]
    assert names == ["Cocos nucifera", "Abrus precatorius"]
