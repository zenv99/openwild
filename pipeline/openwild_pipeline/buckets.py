"""Data buckets: edible / poisonous / medicinal / psychoactive lists from Wikipedia categories.

Wikipedia editors already sort thousands of articles into categories such as "Edible fruits" or
"Poisonous plants". Each article is linked to a Wikidata item, and taxon items carry the scientific
name (P225). Food items ("Banana") point to their source taxon (P1582). So one walk over a category
gives a list of scientific names with a verdict, in many languages, all under open licences
(Wikipedia CC BY-SA, Wikidata CC0).

Output:
  content/quick/auto_buckets.csv        species-level one-liners (manual files override)
  content/auto_group_rules.yaml         genus-level WARNINGS only (poisonous / deadly / psychoactive)

Safety: poisonous beats edible. A species listed as both becomes "edible_cooked" with a warning that
parts are poisonous. Genus-level hits never unlock eating information.
"""
from __future__ import annotations

import concurrent.futures as cf
import csv
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import yaml

from .http import get_json

WIKIDATA_API = "https://www.wikidata.org/w/api.php"

SPECIES_RANKS = {"Q7432", "Q68947", "Q767728", "Q630771", "Q279749"}  # species, subspecies, variety, subvariety, form
GENUS_RANK = "Q34740"


@dataclass(frozen=True)
class Bucket:
    lang: str
    category: str
    kind: str          # edible | edible_cooked | medicinal | toxic | deadly | psychoactive | cordage
    label: str         # short human text used in the one-liner
    depth: int = 1


BUCKETS: list[Bucket] = [
    # --- edible (English Wikipedia)
    Bucket("en", "Edible fruits", "edible", "edible fruit", 2),
    Bucket("en", "Tropical fruit", "edible", "tropical fruit"),
    Bucket("en", "Berries", "edible", "edible berry"),
    Bucket("en", "Edible nuts and seeds", "edible", "edible nut or seed", 2),
    Bucket("en", "Edible flowers", "edible", "edible flower"),
    Bucket("en", "Herbs", "edible", "culinary herb"),
    Bucket("en", "Spices", "edible", "spice plant", 2),
    Bucket("en", "Edible fungi", "edible", "edible mushroom", 2),
    Bucket("en", "Leaf vegetables", "edible_cooked", "leaf vegetable"),
    Bucket("en", "Root vegetables", "edible_cooked", "root vegetable"),
    Bucket("en", "Vegetables", "edible_cooked", "vegetable"),
    Bucket("en", "Tropical agriculture", "edible_cooked", "tropical crop"),
    Bucket("en", "Fruits originating in Asia", "edible", "fruit of Asia"),
    Bucket("en", "Crops originating from Asia", "edible_cooked", "Asian crop"),
    Bucket("en", "Crops originating from the Americas", "edible_cooked", "crop"),
    Bucket("en", "Crops originating from Africa", "edible_cooked", "crop"),
    Bucket("en", "Plants used in Native American cuisine", "edible_cooked", "traditional food plant"),
    Bucket("en", "Bushfood", "edible_cooked", "bush food"),
    # --- medicinal / useful
    Bucket("en", "Medicinal plants", "medicinal", "medicinal plant", 2),
    Bucket("en", "Medicinal fungi", "medicinal", "medicinal mushroom"),
    Bucket("en", "Plants used in traditional Chinese medicine", "medicinal", "used in Chinese medicine"),
    Bucket("en", "Plants used in Ayurveda", "medicinal", "used in Ayurveda"),
    Bucket("en", "Fiber plants", "cordage", "fibre plant"),
    # --- danger
    Bucket("en", "Poisonous plants", "toxic", "poisonous plant", 2),
    Bucket("en", "Poisonous fungi", "toxic", "poisonous mushroom", 2),
    Bucket("en", "Deadly fungi", "deadly", "deadly mushroom"),
    Bucket("en", "Psychoactive fungi", "psychoactive", "psychoactive mushroom"),
    Bucket("en", "Psilocybin mushrooms", "psychoactive", "psychoactive mushroom"),
    Bucket("en", "Entheogens", "psychoactive", "psychoactive plant"),
    Bucket("en", "Hallucinogenic plants", "psychoactive", "psychoactive plant"),
    Bucket("en", "Plant dermatitis", "toxic", "causes skin rash"),
    # --- Indonesian Wikipedia (Bali first)
    Bucket("id", "Buah-buahan", "edible", "buah (edible fruit)", 2),
    Bucket("id", "Buah tropis", "edible", "buah tropis (tropical fruit)"),
    Bucket("id", "Sayuran", "edible_cooked", "sayuran (vegetable)", 2),
    Bucket("id", "Rempah-rempah", "edible", "rempah (spice)"),
    Bucket("id", "Tumbuhan obat", "medicinal", "tumbuhan obat (medicinal plant)", 2),
    Bucket("id", "Tanaman obat", "medicinal", "tanaman obat (medicinal plant)"),
    Bucket("id", "Tumbuhan beracun", "toxic", "tumbuhan beracun (poisonous plant)"),
    Bucket("id", "Jamur yang dapat dimakan", "edible", "jamur (edible mushroom)"),
    Bucket("id", "Jamur beracun", "toxic", "jamur beracun (poisonous mushroom)"),
]

DANGER_KINDS = {"toxic", "deadly", "psychoactive"}
FOOD_KINDS = {"edible", "edible_cooked"}
CAT_PREFIX = {"en": "Category:", "id": "Kategori:"}


def category_pages(lang: str, category: str, depth: int, max_pages: int = 8000) -> dict[str, str]:
    """{page title: wikidata QID} for articles in a category and its subcategories (to `depth`)."""
    api = f"https://{lang}.wikipedia.org/w/api.php"
    pages: dict[str, str] = {}
    seen_cats: set[str] = set()
    frontier = [(f"{CAT_PREFIX[lang]}{category}", 0)]
    while frontier and len(pages) < max_pages:
        cat, d = frontier.pop()
        if cat in seen_cats:
            continue
        seen_cats.add(cat)
        params = {
            "action": "query", "format": "json", "generator": "categorymembers", "gcmtitle": cat,
            "gcmtype": "page|subcat", "gcmlimit": "500", "prop": "pageprops", "ppprop": "wikibase_item",
        }
        while True:
            try:
                data = get_json(api, params)
            except Exception as e:  # noqa: BLE001  (a missing category is fine)
                print(f"  {lang}:{cat}: {e!r}")
                break
            for p in (data.get("query") or {}).get("pages", {}).values():
                if p.get("ns") == 14:
                    if d + 1 <= depth:
                        frontier.append((p["title"], d + 1))
                elif p.get("ns") == 0:
                    q = (p.get("pageprops") or {}).get("wikibase_item")
                    if q:
                        pages[p["title"]] = q
            cont = data.get("continue")
            if not cont:
                break
            params = {**params, **cont}
    return pages


def _claim_values(entity: dict, prop: str) -> list:  # kept for callers that read full entities
    out = []
    for c in (entity.get("claims") or {}).get(prop, []):
        v = ((c.get("mainsnak") or {}).get("datavalue") or {}).get("value")
        if isinstance(v, dict) and "id" in v:
            out.append(v["id"])
        elif isinstance(v, str):
            out.append(v)
    return out


WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"


def _sparql_facts(chunk: list[str]) -> dict[str, dict]:
    values = " ".join(f"wd:{q}" for q in chunk)
    query = (
        "SELECT ?item ?name ?rank ?prod WHERE { VALUES ?item { " + values + " } "
        "OPTIONAL { ?item wdt:P225 ?name } OPTIONAL { ?item wdt:P105 ?rank } OPTIONAL { ?item wdt:P1582 ?prod } }"
    )
    d = get_json(WIKIDATA_SPARQL, {"query": query, "format": "json"})
    out: dict[str, dict] = {}
    for b in d["results"]["bindings"]:
        q = b["item"]["value"].rsplit("/", 1)[-1]
        f = out.setdefault(q, {"name": None, "rank": None, "product_of": []})
        if "name" in b and not f["name"]:
            f["name"] = b["name"]["value"]
        if "rank" in b and not f["rank"]:
            f["rank"] = b["rank"]["value"].rsplit("/", 1)[-1]
        if "prod" in b:
            p = b["prod"]["value"].rsplit("/", 1)[-1]
            if p not in f["product_of"]:
                f["product_of"].append(p)
    return out


def taxon_facts(qids: list[str], workers: int = 4, chunk_size: int = 200) -> dict[str, dict]:
    """{qid: {name, rank, product_of: [qid]}} from Wikidata (P225, P105, P1582), via small SPARQL lookups."""
    qids = sorted(set(qids))
    chunks = [qids[i:i + chunk_size] for i in range(0, len(qids), chunk_size)]

    def one(chunk):
        try:
            return _sparql_facts(chunk)
        except Exception as e:  # noqa: BLE001
            print(f"  wikidata chunk failed: {e!r}", flush=True)
            return {}

    facts: dict[str, dict] = {}
    with cf.ThreadPoolExecutor(workers) as ex:
        for n, part in enumerate(ex.map(one, chunks), 1):
            facts.update(part)
            if n % 25 == 0:
                print(f"  wikidata {n}/{len(chunks)} lookups", flush=True)
    return facts


def resolve_taxa(qids: list[str]) -> dict[str, list[tuple[str, str]]]:
    """{qid: [(scientific name, 'species'|'genus')]}, following food items to their source taxon."""
    facts = taxon_facts(qids)
    products = sorted({t for f in facts.values() if not f["name"] for t in f["product_of"]} - set(facts))
    if products:
        facts.update(taxon_facts(products))

    def as_taxon(f) -> tuple[str, str] | None:
        if not f or not f["name"]:
            return None
        if f["rank"] in SPECIES_RANKS:
            parts = f["name"].split()
            return (" ".join(parts[:2]), "species") if len(parts) >= 2 else None
        if f["rank"] == GENUS_RANK:
            return (f["name"].split()[0], "genus")
        return None

    out: dict[str, list[tuple[str, str]]] = {}
    for q in qids:
        f = facts.get(q)
        hits = []
        t = as_taxon(f)
        if t:
            hits.append(t)
        elif f:
            hits += [x for x in (as_taxon(facts.get(p)) for p in f["product_of"]) if x]
        if hits:
            out[q] = hits
    return out


def collect(buckets: list[Bucket] = BUCKETS) -> tuple[dict[str, dict], dict[str, dict]]:
    """Walk every bucket. Returns (species hits, genus hits): {name: {kind: set(labels)}}."""
    species: dict[str, dict] = defaultdict(lambda: defaultdict(set))
    genera: dict[str, dict] = defaultdict(lambda: defaultdict(set))
    per_bucket: list[tuple[Bucket, dict]] = []
    all_q: set[str] = set()
    for b in buckets:
        pages = category_pages(b.lang, b.category, b.depth)
        print(f"{b.lang}:{b.category}: {len(pages)} articles", flush=True)
        per_bucket.append((b, pages))
        all_q |= set(pages.values())
    taxa = resolve_taxa(sorted(all_q))
    for b, pages in per_bucket:
        n = 0
        for q in pages.values():
            for name, rank in taxa.get(q, []):
                (species if rank == "species" else genera)[name][b.kind].add(b.label)
                n += 1
        print(f"  {b.lang}:{b.category} -> {n} taxa")
    return species, genera


def _join(labels) -> str:
    return ", ".join(sorted(labels)[:3])


def verdict_row(name: str, kinds: dict[str, set]) -> dict:
    """One quick-content row. Danger always wins over food."""
    food = set().union(*(kinds.get(k, set()) for k in FOOD_KINDS))
    med = kinds.get("medicinal", set())
    uses = []
    if food:
        uses.append("food")
    if med:
        uses.append("medicinal")
    if kinds.get("cordage"):
        uses.append("cordage")
    benefit_bits = []
    if food:
        benefit_bits.append(f"Listed as {_join(food)}")
    if med:
        benefit_bits.append(f"used as a {_join(med)}" if not food else f"also a {_join(med)}")
    if kinds.get("cordage"):
        benefit_bits.append("fibres usable for cordage")
    benefit = "; ".join(benefit_bits)
    if benefit:
        benefit = benefit[0].upper() + benefit[1:]

    if kinds.get("deadly"):
        v, harm = "deadly", f"Listed as a {_join(kinds['deadly'])}. Can kill."
        benefit, uses = "", []
    elif kinds.get("psychoactive"):
        v, harm = "psychoactive", f"Listed as a {_join(kinds['psychoactive'])}. Can cause confusion, panic and poisoning; illegal in many countries."
        benefit, uses = "", [u for u in uses if u == "cordage"]
    elif kinds.get("toxic"):
        if food:
            v = "edible_cooked"
            harm = (f"Also listed as a {_join(kinds['toxic'])}: some parts, or the raw plant, are poisonous. "
                    "Eat only parts known to be edible, well cooked.")
        else:
            v, harm = "toxic", f"Listed as a {_join(kinds['toxic'])}."
            benefit, uses = "", [u for u in uses if u == "cordage"]
    elif kinds.get("edible"):
        v, harm = "edible", ""
    elif kinds.get("edible_cooked"):
        v, harm = "edible_cooked", ""
    elif med:
        v, harm = "medicinal", ""
    else:
        v, harm = "inedible", ""
    return {"scientific_name": name, "verdict": v, "benefit": benefit, "harm": harm,
            "survival": ";".join(uses), "source": "Wikipedia categories + Wikidata"}


def genus_rule(name: str, kinds: dict[str, set]) -> dict | None:
    """Genus-level WARNING rule, only for danger buckets."""
    if kinds.get("deadly"):
        tox, what = "deadly", f"This genus includes a {_join(kinds['deadly'])}."
    elif kinds.get("toxic"):
        tox, what = "serious", f"This genus includes a {_join(kinds['toxic'])}."
    elif kinds.get("psychoactive"):
        tox, what = "serious", f"This genus includes a {_join(kinds['psychoactive'])}; toxic lookalikes are common."
    else:
        return None
    return {
        "taxon": name, "rank": "genus", "kingdom": "Fungi" if "mushroom" in what else "Plantae",
        "toxicity": tox, "psychoactive": bool(kinds.get("psychoactive")) or None,
        "warning": what + " Treat every species in it as unsafe unless identified with certainty.",
        "onset": "Varies by species and amount.",
        "what_to_do": "Seek emergency medical care." if tox == "deadly" else "Seek medical care if symptoms appear.",
        "source": "Wikipedia categories + Wikidata",
    }


def write_outputs(species: dict, genera: dict, quick_csv: Path, rules_yaml: Path) -> tuple[int, int]:
    rows = [verdict_row(n, k) for n, k in sorted(species.items())]
    quick_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(quick_csv, "w", newline="", encoding="utf-8") as f:
        f.write("# AUTO-GENERATED from Wikipedia categories (CC BY-SA) + Wikidata (CC0) by `cli buckets`.\n")
        f.write("# Do not edit: hand-written content/quick files and Wikidata mushroom edibility override these rows.\n")
        w = csv.DictWriter(f, fieldnames=["scientific_name", "verdict", "benefit", "harm", "survival", "source"])
        w.writeheader()
        w.writerows(rows)
    rules = [r for r in (genus_rule(n, k) for n, k in sorted(genera.items())) if r]
    for r in rules:
        if r["psychoactive"] is None:
            del r["psychoactive"]
    rules_yaml.write_text(
        "# AUTO-GENERATED genus WARNINGS from Wikipedia categories + Wikidata by `cli buckets`.\n"
        "# Rules here only warn. Hand-written content/group_rules.yaml overrides any genus listed here.\n"
        + yaml.safe_dump(rules, sort_keys=False, allow_unicode=True, width=200)
    )
    return len(rows), len(rules)
