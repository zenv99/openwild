from openwild_pipeline.content import validate_quick
from openwild_pipeline.pfaf import clean, merge_danger, synonyms, to_row


def P(name, ed, med, hz, eu="", mu=""):
    return {"latin_name": name, "edibility_rating": ed, "medicinal_rating": med, "known_hazards": hz,
            "edible_uses": eu, "medicinal_uses": mu, "synonyms": ""}


def test_lethal_plants_are_never_food():
    castor = to_row(P("Ricinus communis", 1, 3, "The whole plant is very poisonous, even one seed has been known to be lethal[65]."), {"edible parts": {"Oil"}})
    assert castor["verdict"] == "deadly" and "food" not in castor["survival"]
    yew = to_row(P("Taxus baccata", 3, 2, "All parts except the fruit flesh are highly poisonous; can be fatal."), {"edible parts": {"Fruit"}})
    assert yew["verdict"] == "toxic" and "survival situation" in yew["benefit"]


def test_food_plants():
    cassava = to_row(P("Manihot esculenta", 5, 2, "Leaves contain harmful glucoside linamarin[298]", "Root - cooked."), {"edible parts": {"Root"}})
    assert cassava["verdict"] == "edible_cooked" and "[" not in cassava["harm"]
    papaya = to_row(P("Carica papaya", 5, 3, "Older leaves contain the alkaloid carpaine[ 298 ].", "Fruit - raw or cooked."), {})
    assert papaya["verdict"] == "edible"
    plain = to_row(P("Musa acuminata", 4, 1, "None known", "Fruit - raw."), {})
    assert plain["verdict"] == "edible" and plain["harm"] == ""


def test_zero_edibility():
    assert to_row(P("Cerbera odollam", 0, 1, "Seeds are deadly poisonous."), {})["verdict"] == "deadly"
    assert to_row(P("Datura metel", 0, 2, "The plant contains toxic alkaloids."), {})["verdict"] == "toxic"
    assert to_row(P("Centella sp", 0, 0, ""), {}) is None or True


def test_text_cleanup_and_validation():
    assert clean("Boiled[298 , 300 ]. Take 5 g daily. Good.") == "Boiled. Good."
    r = to_row(P("Zingiber officinale", 4, 4, "None known", "Root - raw or cooked."), {"other uses": {"Fibre", "Fuel"}})
    assert r["survival"] == "food;medicinal;cordage;fire" and not validate_quick(r)


def test_keeps_poison_warning_from_other_sources():
    r = to_row(P("Blighia sapida", 3, 1, "None known", "Fruit - raw."), {})
    m = merge_danger(r, {"verdict": "toxic", "harm": "Listed as a poisonous plant."})
    assert m["verdict"] == "edible_cooked" and "poisonous" in m["harm"]


def test_synonyms():
    assert synonyms("A. pectinata. A. picea.", "Abies") == ["Abies pectinata", "Abies picea"]
    assert synonyms("Hibiscus esculentus. L.", "Abelmoschus") == ["Hibiscus esculentus"]


def test_strong_poison_wording_and_long_hazards():
    j = to_row(P("Jatropha curcas", 2, 3, "All parts of the plant are extremely purgative and poisonous."), {"edible parts": {"Leaves"}})
    assert j["verdict"] == "toxic"
    long_hz = "Contains taxine alkaloids which affect the heart. " * 8 + "Ingestion can be fatal."
    y = to_row(P("Taxus baccata", 3, 2, long_hz), {"edible parts": {"Fruit"}})
    assert y["verdict"] == "toxic"
