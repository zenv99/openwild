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
