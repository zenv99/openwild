import json

from openwild_pipeline.pack import SpeciesRecord, build_pack
from openwild_pipeline.publish import pack_entry, update_index


def test_index_entry_and_replace(tmp_path):
    pack = build_pack(tmp_path / "X.owpack", "XX", "Region X", 7, [SpeciesRecord(scientific_name="Musa acuminata", kingdom="Plantae")],
                      {}, guide_only=True, group_rules={}, quick_rows={})
    e = pack_entry(pack, "https://example/XX.owpack")
    assert e["region_id"] == "XX" and e["version"] == 7 and e["species_count"] == 1 and len(e["sha256"]) == 64
    idx = tmp_path / "index.json"
    update_index(idx, {**e, "version": 1})
    update_index(idx, {**e, "region_id": "AA"})
    update_index(idx, e)
    packs = json.loads(idx.read_text())["packs"]
    assert [p["region_id"] for p in packs] == ["AA", "XX"] and packs[1]["version"] == 7
