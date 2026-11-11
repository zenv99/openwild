from openwild_pipeline.content import validate_quick
from openwild_pipeline.kew_useful import read_table, to_rows


def test_mapping_is_conservative():
    data = (b"binomial_acc_name,HumanFood,Medicines,Poisons,Fuels,AnimalFood\n"
            b"Manihot esculenta Crantz,1,1,1,0,1\n"
            b"Cocos nucifera L.,1,1,0,1,0\n"
            b"Cerbera odollam Gaertn.,0,1,1,0,0\n"
            b"Centella asiatica (L.) Urb.,0,1,0,0,0\n"
            b"Pennisetum purpureum Schumach.,0,0,0,0,1\n")
    rows = {r["scientific_name"]: r for r in to_rows(read_table("x.csv", data))}
    assert rows["Manihot esculenta"]["verdict"] == "edible_cooked" and "poison" in rows["Manihot esculenta"]["harm"]
    assert rows["Cocos nucifera"]["verdict"] == "edible_cooked" and rows["Cocos nucifera"]["survival"] == "food;medicinal;fire"
    assert rows["Cerbera odollam"]["verdict"] == "toxic" and rows["Cerbera odollam"]["benefit"] == ""
    assert rows["Centella asiatica"]["verdict"] == "medicinal"
    assert "Pennisetum purpureum" not in rows
    assert all(not validate_quick(r) for r in rows.values())


def test_genus_species_columns():
    data = b"genus;species;Human food;Medicines;Poisons\nMusa;acuminata;1;0;0\n"
    rows = to_rows(read_table("y.csv", data))
    assert rows[0]["scientific_name"] == "Musa acuminata"
