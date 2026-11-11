from openwild_pipeline.content import validate_quick
from openwild_pipeline.evidence import combine


def pf(ed=0, med=0, parts="", prep="", hazards="", uses=""):
    return {"edibility": str(ed), "medicinal": str(med), "edible_parts": parts, "prep": prep, "hazards": hazards,
            "uses": uses, "url": "https://pfaf.org/x"}


KEW_FOOD = {"verdict": "edible_cooked", "benefit": "Recorded as human food (Kew useful plants)", "harm": ""}
KEW_FOOD_POISON = {**KEW_FOOD, "harm": "Also recorded as a poison: some parts or raw material are toxic."}
WIKI_FOOD = {"verdict": "edible", "benefit": "Listed as edible fruit", "harm": "", "survival": "food"}
WIKI_POISON = {"verdict": "toxic", "benefit": "", "harm": "Listed as a poisonous plant.", "survival": ""}


def test_rosary_pea_is_not_food():
    row, _ = combine("Abrus precatorius", None, KEW_FOOD_POISON, WIKI_POISON)
    assert row["verdict"] == "toxic" and "not safe to rely on" in row["harm"] and not validate_quick(row)


def test_lethal_and_strong():
    castor, _ = combine("Ricinus communis", pf(1, 3, "oil", "cooked", "lethal;strong;poison"), KEW_FOOD, WIKI_POISON)
    assert castor["verdict"] == "deadly" and castor["survival"] == ""
    yew, _ = combine("Taxus baccata", pf(3, 2, "fruit", "raw", "strong;heart"), None, None)
    assert yew["verdict"] == "toxic" and "survival situation" in yew["benefit"]


def test_food_plants_and_kew_poison_use():
    papaya, led = combine("Carica papaya", pf(5, 3, "fruit;leaves", "raw;cooked", "alkaloid"), KEW_FOOD_POISON, WIKI_FOOD)
    assert papaya["verdict"] == "edible" and "Kew" not in papaya["harm"] and papaya["evidence"] == "3"
    assert any("not counted" in e["claim"] for e in led)
    cassava, _ = combine("Manihot esculenta", pf(5, 2, "leaves;root", "cooked;soaked", "poison;cyanide"), KEW_FOOD_POISON, WIKI_FOOD)
    assert cassava["verdict"] == "edible_cooked" and "cyanide" in cassava["harm"]
    single, _ = combine("Rare plant", None, KEW_FOOD, None)
    assert single["verdict"] == "edible_cooked" and "one source" in single["benefit"]


def test_ledger_records_each_claim():
    _, led = combine("Musa acuminata", pf(4, 1, "fruit", "raw"), KEW_FOOD, WIKI_FOOD)
    assert {(e["source"], e["claim"]) for e in led} >= {("PFAF", "food"), ("Kew", "food"), ("Wikipedia/Commons", "food")}


def test_no_pfaf_wording():
    row, _ = combine("Musa acuminata", pf(4, 1, "fruit", "raw"), KEW_FOOD, WIKI_FOOD)
    assert "PFAF rating" not in row["benefit"] and "[" not in row["benefit"]
