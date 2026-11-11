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
    W = "http://www.wikidata.org/entity/"

    def fake(url, params=None, **_):
        rows = {
            "Q1": [{"prod": W + "Q2"}],                                     # "Banana" (food item)
            "Q2": [{"name": "Musa acuminata", "rank": W + "Q7432"}],
            "Q3": [{"name": "Datura", "rank": W + "Q34740"}],
        }
        asked = [t.split(":")[1] for t in params["query"].split("{ ")[2].split(" }")[0].split()]
        return {"results": {"bindings": [
            {"item": {"value": W + q}, **{k: {"value": v} for k, v in r.items()}}
            for q in asked for r in rows.get(q, [])
        ]}}

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


def test_commons_titles_are_taxa(monkeypatch):
    def fake(url, params=None, **_):
        return {"query": {"categorymembers": [
            {"title": "Category:Datura metel"}, {"title": "Category:Dieffenbachia"},
            {"title": "Category:Poisonous plants of Australia"}, {"title": "Category:Ricinus communis"},
        ]}}

    monkeypatch.setattr(buckets, "get_json", fake)
    assert buckets.commons_taxa("Poisonous plants") == [
        ("Datura metel", "species"), ("Dieffenbachia", "genus"), ("Ricinus communis", "species")]


def test_kingdom_tag():
    assert buckets.row_kingdom({"edible": {"edible fruit"}}) == "Plantae"
    assert buckets.row_kingdom({"edible": {"edible mushroom"}}) == "Fungi"
    assert buckets.row_kingdom({"edible": {"edible fruit"}, "toxic": {"poisonous mushroom"}}) == ""


def test_pack_ignores_row_from_other_kingdom(tmp_path):
    import json
    import sqlite3

    from openwild_pipeline.pack import SpeciesRecord, build_pack

    quick = {"colletotrichum musae": {"scientific_name": "Colletotrichum musae", "verdict": "edible",
                                      "benefit": "Listed as edible fruit", "harm": "", "survival": "food", "kingdom": "Plantae"}}
    recs = [SpeciesRecord(scientific_name="Colletotrichum musae", kingdom="Fungi")]
    out = build_pack(tmp_path / "K.owpack", "K", "K", 1, recs, {}, guide_only=True, group_rules={}, quick_rows=quick)
    content = json.loads(sqlite3.connect(out).execute("SELECT json FROM content").fetchone()[0])
    assert content.get("edibility") != "edible"


def test_pack_matches_synonym_by_gbif_key(tmp_path):
    import json
    import sqlite3

    from openwild_pipeline.pack import SpeciesRecord, build_pack

    quick = {"cocos nucifera": {"scientific_name": "Cocos nucifera", "verdict": "edible", "benefit": "Listed as edible fruit",
                                "harm": "", "survival": "food", "kingdom": "Plantae", "gbif_key": "2735117"}}
    recs = [SpeciesRecord(scientific_name="Cocos nucifera var. synonymus", kingdom="Plantae", gbif_key=2735117)]
    out = build_pack(tmp_path / "S.owpack", "S", "S", 1, recs, {}, guide_only=True, group_rules={}, quick_rows=quick)
    content = json.loads(sqlite3.connect(out).execute("SELECT json FROM content").fetchone()[0])
    assert content["edibility"] == "edible" and content["level"] == "quick"
