from openwild_pipeline import buckets
from openwild_pipeline.content import validate_quick, validate_rule


def test_poison_beats_food():
    r = buckets.verdict_row("Manihot esculenta", {"edible_cooked": {"root vegetable"}, "toxic": {"poisonous plant"}})
    assert r["verdict"] == "edible_cooked" and "poisonous" in r["harm"]
    assert not validate_quick(r)


def test_deadly_and_psychoactive_drop_benefits():
    r = buckets.verdict_row("Amanita phalloides", {"deadly": {"deadly mushroom"}, "edible": {"x"}})
    assert r["verdict"] == "deadly" and r["benefit"] == "" and r["survival"] == ""
    p = buckets.verdict_row("Psilocybe cubensis", {"psychoactive": {"psychoactive mushroom"}, "medicinal": {"m"}})
    assert p["verdict"] == "psychoactive" and not validate_quick(p)


def test_food_and_medicine():
    r = buckets.verdict_row("Zingiber officinale", {"edible": {"spice plant"}, "medicinal": {"medicinal plant"}})
    assert r["verdict"] == "edible" and r["survival"] == "food;medicinal" and "medicinal" in r["benefit"]
    assert not validate_quick(r)


def test_genus_rules_only_warn():
    assert buckets.genus_rule("Musa", {"edible": {"edible fruit"}}) is None
    g = buckets.genus_rule("Dieffenbachia", {"toxic": {"poisonous plant"}})
    assert g["toxicity"] == "serious" and not validate_rule(g)


def test_resolve_follows_food_items(monkeypatch):
    def fake(url, params=None, **_):
        ids = params["ids"].split("|")
        ents = {
            "Q1": {"claims": {"P1582": [{"mainsnak": {"datavalue": {"value": {"id": "Q2"}}}}]}},  # "Banana" (food)
            "Q2": {"claims": {"P225": [{"mainsnak": {"datavalue": {"value": "Musa acuminata"}}}],
                              "P105": [{"mainsnak": {"datavalue": {"value": {"id": "Q7432"}}}}]}},
            "Q3": {"claims": {"P225": [{"mainsnak": {"datavalue": {"value": "Datura"}}}],
                              "P105": [{"mainsnak": {"datavalue": {"value": {"id": "Q34740"}}}}]}},
        }
        return {"entities": {i: ents[i] for i in ids if i in ents}}

    monkeypatch.setattr(buckets, "get_json", fake)
    out = buckets.resolve_taxa(["Q1", "Q3"])
    assert out["Q1"] == [("Musa acuminata", "species")]
    assert out["Q3"] == [("Datura", "genus")]


def test_category_walk(monkeypatch):
    def fake(url, params=None, **_):
        if params["gcmtitle"] == "Category:Edible fruits":
            return {"query": {"pages": {"1": {"ns": 0, "title": "Mango", "pageprops": {"wikibase_item": "Q9"}},
                                        "2": {"ns": 14, "title": "Category:Citrus"}}}}
        return {"query": {"pages": {"3": {"ns": 0, "title": "Lime", "pageprops": {"wikibase_item": "Q8"}}}}}

    monkeypatch.setattr(buckets, "get_json", fake)
    assert buckets.category_pages("en", "Edible fruits", 1) == {"Mango": "Q9", "Lime": "Q8"}
    assert buckets.category_pages("en", "Edible fruits", 0) == {"Mango": "Q9"}
