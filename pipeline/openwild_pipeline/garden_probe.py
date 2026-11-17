"""How many plants people photograph in Bali (incl. gardens / cultivated, iNaturalist 'captive') are missing
from the Bali species list?"""
import csv
import json

from . import config
from .http import get_json

places = get_json("https://api.inaturalist.org/v1/places/autocomplete", {"q": "Bali"}, cache=False)
for p in (places.get("results") or [])[:5]:
    print("PLACE", p.get("id"), p.get("display_name"), p.get("admin_level"))
pid = next((p["id"] for p in places.get("results") or [] if (p.get("display_name") or "").startswith("Bali")), None)
have = {r["scientific_name"].lower() for r in csv.DictReader(open(config.DATA_REGIONS / "ID-BA" / "species.csv", encoding="utf-8"))}
for captive in ("true", "false"):
    missing, total, n_obs_missing = [], 0, 0
    for page in range(1, 6):
        d = get_json("https://api.inaturalist.org/v1/observations/species_counts",
                     {"place_id": str(pid), "captive": captive, "iconic_taxa": "Plantae,Fungi", "per_page": "500", "page": str(page)}, cache=False)
        res = d.get("results") or []
        for r in res:
            t = r["taxon"]
            if t.get("rank") != "species":
                continue
            total += 1
            if t["name"].lower() not in have:
                missing.append((r["count"], t["name"], t.get("preferred_common_name")))
                n_obs_missing += r["count"]
        if len(res) < 500:
            break
    missing.sort(reverse=True)
    print(f"GARDEN captive={captive}: {total} species seen in Bali, {len(missing)} missing from our list ({n_obs_missing} sightings)")
    for m in missing[:40]:
        print("GARDEN   ", json.dumps(m, ensure_ascii=False))
