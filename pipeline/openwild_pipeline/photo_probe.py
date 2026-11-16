"""Find EVERY photo of a few exact plants, under any licence, so we can use the open ones and ask the owners
of the others for permission. Prints one line per photo: source, licence, owner, page."""
from __future__ import annotations

import json
import sys

from . import config
from .http import get_json

NAMES = {
    "Strychnos villosa": [],
    "Strophanthus puberulus": [],
    "Cycas montana": [],
    "Pimpinella javana": ["Murrithia cordata", "Anisometros alpina"],
    "Psammogeton involucratus": ["Pimpinella involucrata", "Psammogeton involucratum"],
    "Inocybe subgeophylla": [],
}


def probe(name: str, alts: list[str]) -> list[dict]:
    out = []
    for n in [name] + alts:
        try:
            d = get_json("https://api.inaturalist.org/v1/observations", {"taxon_name": n, "photos": "true", "per_page": "50"}, retries=2, timeout=40)
            for o in d.get("results") or []:
                tn = ((o.get("taxon") or {}).get("name") or "")
                for p in o.get("photos") or []:
                    out.append({"src": "inaturalist", "as": tn, "lic": p.get("license_code") or "all-rights-reserved",
                                "owner": (o.get("user") or {}).get("login"), "page": f"https://www.inaturalist.org/observations/{o.get('id')}",
                                "grade": o.get("quality_grade"), "place": o.get("place_guess")})
        except Exception as e:  # noqa: BLE001
            print("inat", n, e)
        try:
            m = get_json(f"{config.GBIF_API}/species/match", {"name": n, "strict": "true"}, retries=2)
            key = m.get("usageKey")
            if key:
                d = get_json(f"{config.GBIF_API}/occurrence/search", {"taxonKey": str(key), "mediaType": "StillImage", "limit": "100"}, retries=2)
                for o in d.get("results") or []:
                    for md in o.get("media") or []:
                        out.append({"src": "gbif:" + str(o.get("basisOfRecord")), "as": o.get("scientificName"),
                                    "lic": md.get("license") or o.get("license"), "owner": md.get("rightsHolder") or md.get("creator") or o.get("institutionCode"),
                                    "page": f"https://www.gbif.org/occurrence/{o.get('key')}", "url": md.get("identifier")})
        except Exception as e:  # noqa: BLE001
            print("gbif", n, e)
        try:
            d = get_json("https://commons.wikimedia.org/w/api.php", {"action": "query", "list": "search", "srsearch": n,
                                                                     "srnamespace": "6", "srlimit": "50", "format": "json"}, retries=2)
            for h in (d.get("query") or {}).get("search") or []:
                out.append({"src": "commons", "as": n, "lic": "see page", "page": "https://commons.wikimedia.org/wiki/" + h["title"].replace(" ", "_")})
        except Exception as e:  # noqa: BLE001
            print("commons", n, e)
    return out


if __name__ == "__main__":
    rep = {}
    for name, alts in NAMES.items():
        r = probe(name, alts)
        rep[name] = r
        print(f"PROBE {name}: {len(r)} photos")
        for x in r[:60]:
            print("PROBE   ", json.dumps(x, ensure_ascii=False))
    json.dump(rep, open(sys.argv[1] if len(sys.argv) > 1 else "/tmp/probe.json", "w"), indent=1)
