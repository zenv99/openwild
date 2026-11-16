"""Why does exact_specimens() find nothing for a name? Print GBIF's match and the media it returns."""
import json
import sys

from . import config
from .http import get_json

for name in sys.argv[1:]:
    m = get_json(f"{config.GBIF_API}/species/match", {"name": name, "strict": "true"}, cache=False)
    print("MATCH", name, json.dumps({k: m.get(k) for k in ("usageKey", "acceptedUsageKey", "rank", "status", "matchType", "scientificName")}))
    for key in {m.get("usageKey"), m.get("acceptedUsageKey")} - {None}:
        d = get_json(f"{config.GBIF_API}/occurrence/search", {"taxonKey": str(key), "mediaType": "StillImage",
                                                               "basisOfRecord": "PRESERVED_SPECIMEN", "limit": "100"}, cache=False, timeout=90)
        print("  key", key, "count", d.get("count"))
        for o in (d.get("results") or [])[:8]:
            for md in (o.get("media") or [])[:1]:
                print("   ", o.get("institutionCode"), "| media lic:", md.get("license"), "| occ lic:", o.get("license"),
                      "| ok:", config.photo_license(md.get("license")) or config.photo_license(o.get("license")), "|", (md.get("identifier") or "")[:90])
