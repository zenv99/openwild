from openwild_pipeline.parts import build_parts


def by_part(rows):
    return {r["part"]: r["status"] for r in rows}


def test_deadly_plant_never_food():
    c = {"toxicity": "deadly", "edibility": "deadly", "if_eaten": {"harms": {"toxic_parts": ["whole plant, especially seeds"]}}}
    pf = {"edibility": "2", "edible_parts": "seed;leaves", "prep": "cooked", "hazards": "lethal"}
    rows = by_part(build_parts(c, pf, False))
    assert set(rows.values()) == {"deadly"} and len(rows) == 8


def test_harm_wins_over_food_report():
    # Cassava-like: roots eaten cooked, leaves eaten, but raw roots poisonous.
    c = {"toxicity": "mild", "edibility": "edible_with_preparation",
         "if_eaten": {"harms": {"symptoms": "Raw roots contain cyanide."}}}
    pf = {"edibility": "4", "edible_parts": "root;leaves", "prep": "cooked;soaked", "hazards": "cyanide"}
    rows = by_part(build_parts(c, pf, False))
    assert rows["roots"] == "edible_cooked"  # poisonous raw, eaten cooked
    assert rows["leaves"] in ("edible_cooked", "caution", "experts_only")
    assert "seeds" not in rows  # no information -> not listed


def test_poisonous_plant_food_report_is_experts_only():
    c = {"toxicity": "serious", "edibility": "toxic", "if_eaten": {"harms": {"symptoms": "Seeds cause vomiting."}}}
    pf = {"edibility": "1", "edible_parts": "fruit", "prep": "cooked", "hazards": "poison"}
    rows = by_part(build_parts(c, pf, False))
    assert rows == {"seeds": "toxic", "fruit": "experts_only"}


def test_plain_food_plant():
    c = {"toxicity": "none", "edibility": "edible", "if_eaten": {"benefits": {"nutrition": "Young leaves and fruit eaten."}}}
    pf = {"edibility": "4", "edible_parts": "fruit;leaves;flowers", "prep": "raw;cooked", "hazards": ""}
    rows = build_parts(c, pf, False)
    assert by_part(rows) == {"fruit": "edible", "flowers": "edible", "leaves": "edible"}
    assert "raw or cooked" in rows[0]["note"]


def test_fungi_skipped_and_all_parts_phrase():
    assert build_parts({"toxicity": "deadly"}, None, True) == []
    c = {"toxicity": "serious", "edibility": "toxic", "if_eaten": {"harms": {"symptoms": "All parts are poisonous."}}}
    assert set(by_part(build_parts(c, None, False)).values()) == {"toxic"}


def test_raw_qualifier_does_not_leak_to_other_clauses():
    c = {"toxicity": "serious", "edibility": "edible_with_preparation",
         "if_eaten": {"harms": {"symptoms": "Raw leaves burn the mouth; seeds are poisonous."}}}
    pf = {"edibility": "3", "edible_parts": "leaves;seed", "prep": "cooked", "hazards": "poison"}
    rows = by_part(build_parts(c, pf, False))
    assert rows["leaves"] == "edible_cooked" and rows["seeds"] == "toxic"


def test_wikipedia_facts_and_safety():
    from openwild_pipeline.wiki_parts import facts_from_text

    en = ("The seeds are highly poisonous and can be fatal. Young leaves are eaten as a vegetable. "
          "The leaves are food for caterpillars of several moths. The raw tubers are toxic but are eaten after boiling.")
    f = set(facts_from_text(en, "en"))
    assert ("seeds", "harm", "") in f and ("leaves", "food", "") in f and ("roots", "harm", "raw") in f
    idn = "Bijinya beracun. Daun muda dimakan sebagai lalapan. Daunnya juga dimakan ternak."
    g = set(facts_from_text(idn, "id"))
    assert ("seeds", "harm", "") in g and any(p == "leaves" and k == "food" for p, k, _ in g)
    # Wikipedia food alone is never plain "edible", and harm always wins.
    c = {"toxicity": "unknown", "edibility": "unknown"}
    rows = by_part(build_parts(c, None, False, [{"part": "leaves", "kind": "food", "qualifier": ""},
                                               {"part": "seeds", "kind": "harm", "qualifier": ""},
                                               {"part": "seeds", "kind": "food", "qualifier": ""}]))
    assert rows == {"seeds": "harmful", "leaves": "caution"}


def test_curated_qualifier_beats_general_wikipedia_warning():
    # Papaya-like: curated says unripe fruit is harmful; Wikipedia just says "the fruit ... toxic".
    c = {"toxicity": "mild", "edibility": "edible",
         "if_eaten": {"harms": {"symptoms": "Unripe fruit latex irritates."}, "benefits": {"nutrition": "Ripe fruit eaten."}}}
    pf = {"edibility": "4", "edible_parts": "fruit;leaves", "prep": "raw;cooked", "hazards": ""}
    wiki = [{"part": "fruit", "kind": "harm", "qualifier": ""}, {"part": "leaves", "kind": "harm", "qualifier": ""},
            {"part": "seeds", "kind": "harm", "qualifier": ""}]
    rows = by_part(build_parts(c, pf, False, wiki))
    assert rows["fruit"] == "caution"          # ripe only (curated) – not overruled
    assert rows["leaves"] == "caution"         # eaten per structured source, Wikipedia warns -> with care
    assert rows["seeds"] == "harmful"          # nothing else known -> Wikipedia harm stands
