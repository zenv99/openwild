"""Extra reference photos for houseplants, tropical garden plants and Southeast Asian fungi.

The occurrence list behind a country pack is mostly wild records. People also photograph potted
plants, street trees and mushrooms. This module pulls openly licensed photos (CC0, CC BY, CC BY-SA,
and CC BY-NC only because the app is non-commercial), keeps a photographer and a source URL on
every one, and adds their DINOv2 fingerprints to a copy of the published pack.

Plants For A Future and tropical.theferns.info are never requested. New fungi do not gain an
edibility claim: a genus or species harm warning already in the safety files may be attached,
and anything that would call a mushroom edible is left as unknown.
"""
from __future__ import annotations

import csv
import json
import re
import shutil
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

from . import config
from .cultivated import FIELDS as CULTIVATED_FIELDS
from .cultivated import cultivated_path

# (category, name to look up). A synonym resolves to the GBIF accepted species when that name differs.
TARGETS: list[tuple[str, str]] = [
    # Houseplants and indoor ornamentals.
    ("houseplant", "Asplenium nidus"),
    ("houseplant", "Monstera deliciosa"),
    ("houseplant", "Monstera adansonii"),
    ("houseplant", "Dieffenbachia seguine"),
    ("houseplant", "Epipremnum aureum"),
    ("houseplant", "Epipremnum pinnatum"),
    ("houseplant", "Philodendron hederaceum"),
    ("houseplant", "Philodendron bipinnatifidum"),
    ("houseplant", "Dracaena trifasciata"),
    ("houseplant", "Sansevieria trifasciata"),
    ("houseplant", "Dracaena fragrans"),
    ("houseplant", "Dracaena angolensis"),
    ("houseplant", "Sansevieria cylindrica"),
    ("houseplant", "Dracaena reflexa"),
    ("houseplant", "Spathiphyllum wallisii"),
    ("houseplant", "Spathiphyllum cochlearispathum"),
    ("houseplant", "Zamioculcas zamiifolia"),
    ("houseplant", "Ficus elastica"),
    ("houseplant", "Ficus lyrata"),
    ("houseplant", "Ficus benjamina"),
    ("houseplant", "Ficus microcarpa"),
    ("houseplant", "Goeppertia ornata"),
    ("houseplant", "Calathea ornata"),
    ("houseplant", "Goeppertia roseopicta"),
    ("houseplant", "Goeppertia makoyana"),
    ("houseplant", "Anthurium andraeanum"),
    ("houseplant", "Anthurium crystallinum"),
    ("houseplant", "Aglaonema commutatum"),
    ("houseplant", "Aglaonema modestum"),
    ("houseplant", "Syngonium podophyllum"),
    ("houseplant", "Nephrolepis exaltata"),
    ("houseplant", "Nephrolepis cordifolia"),
    ("houseplant", "Chlorophytum comosum"),
    ("houseplant", "Begonia maculata"),
    ("houseplant", "Begonia cucullata"),
    ("houseplant", "Phalaenopsis amabilis"),
    ("houseplant", "Dendrobium bigibbum"),
    ("houseplant", "Aloe vera"),
    ("houseplant", "Crassula ovata"),
    ("houseplant", "Echeveria elegans"),
    ("houseplant", "Haworthiopsis attenuata"),
    ("houseplant", "Schlumbergera truncata"),
    ("houseplant", "Opuntia microdasys"),
    ("houseplant", "Peperomia obtusifolia"),
    ("houseplant", "Peperomia argyreia"),
    ("houseplant", "Peperomia caperata"),
    ("houseplant", "Pilea peperomioides"),
    ("houseplant", "Tradescantia zebrina"),
    ("houseplant", "Maranta leuconeura"),
    ("houseplant", "Hoya carnosa"),
    ("houseplant", "Beaucarnea recurvata"),
    ("houseplant", "Caladium bicolor"),
    ("houseplant", "Scindapsus pictus"),
    ("houseplant", "Rhaphidophora tetrasperma"),
    ("houseplant", "Adiantum raddianum"),
    ("houseplant", "Platycerium bifurcatum"),
    ("houseplant", "Kalanchoe blossfeldiana"),
    ("houseplant", "Euphorbia milii"),
    ("houseplant", "Curio rowleyanus"),
    # Tropical garden and landscape plants.
    ("garden", "Plumeria rubra"),
    ("garden", "Plumeria obtusa"),
    ("garden", "Bougainvillea spectabilis"),
    ("garden", "Bougainvillea glabra"),
    ("garden", "Hibiscus rosa-sinensis"),
    ("garden", "Ixora coccinea"),
    ("garden", "Ixora javanica"),
    ("garden", "Heliconia rostrata"),
    ("garden", "Heliconia psittacorum"),
    ("garden", "Canna indica"),
    ("garden", "Cordyline fruticosa"),
    ("garden", "Codiaeum variegatum"),
    ("garden", "Pandanus amaryllifolius"),
    ("garden", "Pandanus tectorius"),
    ("garden", "Alpinia purpurata"),
    ("garden", "Alpinia zerumbet"),
    ("garden", "Musa acuminata"),
    ("garden", "Carica papaya"),
    ("garden", "Cocos nucifera"),
    ("garden", "Dypsis lutescens"),
    ("garden", "Roystonea regia"),
    ("garden", "Wodyetia bifurcata"),
    ("garden", "Bambusa vulgaris"),
    ("garden", "Dendrocalamus asper"),
    ("garden", "Lantana camara"),
    ("garden", "Allamanda cathartica"),
    ("garden", "Mussaenda philippica"),
    ("garden", "Gardenia jasminoides"),
    ("garden", "Jasminum sambac"),
    ("garden", "Cananga odorata"),
    ("garden", "Delonix regia"),
    ("garden", "Caesalpinia pulcherrima"),
    ("garden", "Nerium oleander"),
    ("garden", "Duranta erecta"),
    ("garden", "Ruellia simplex"),
    ("garden", "Etlingera elatior"),
    ("garden", "Hellenia speciosa"),
    ("garden", "Acalypha wilkesiana"),
    ("garden", "Polyscias scutellaria"),
    ("garden", "Adenium obesum"),
    ("garden", "Pachira aquatica"),
    ("garden", "Ravenala madagascariensis"),
    ("garden", "Tabernaemontana divaricata"),
    ("garden", "Combretum indicum"),
    ("garden", "Thunbergia grandiflora"),
    ("garden", "Tecoma stans"),
    ("garden", "Spathodea campanulata"),
    ("garden", "Lagerstroemia speciosa"),
    ("garden", "Cassia fistula"),
    ("garden", "Erythrina variegata"),
    ("garden", "Artocarpus altilis"),
    # Fungi of Indonesia and neighbouring Southeast Asia, including cultivated and deadly lookalikes.
    ("fungus", "Amanita princeps"),
    ("fungus", "Amanita javanica"),
    ("fungus", "Amanita hemibapha"),
    ("fungus", "Amanita virginea"),
    ("fungus", "Chlorophyllum molybdites"),
    ("fungus", "Chlorophyllum hortense"),
    ("fungus", "Termitomyces microcarpus"),
    ("fungus", "Termitomyces eurrhizus"),
    ("fungus", "Termitomyces heimii"),
    ("fungus", "Termitomyces clypeatus"),
    ("fungus", "Volvariella volvacea"),
    ("fungus", "Volvariella bombycina"),
    ("fungus", "Auricularia cornea"),
    ("fungus", "Auricularia auricula-judae"),
    ("fungus", "Auricularia nigricans"),
    ("fungus", "Schizophyllum commune"),
    ("fungus", "Pleurotus ostreatus"),
    ("fungus", "Pleurotus djamor"),
    ("fungus", "Pleurotus cystidiosus"),
    ("fungus", "Lentinula edodes"),
    ("fungus", "Ganoderma lucidum"),
    ("fungus", "Ganoderma applanatum"),
    ("fungus", "Psilocybe cubensis"),
    ("fungus", "Coprinus comatus"),
    ("fungus", "Coprinopsis atramentaria"),
    ("fungus", "Marasmius haematocephalus"),
    ("fungus", "Leucocoprinus birnbaumii"),
    ("fungus", "Macrolepiota dolichaula"),
    ("fungus", "Lentinus squarrosulus"),
    ("fungus", "Cookeina sulcipes"),
    ("fungus", "Oudemansiella canarii"),
    ("fungus", "Microporus xanthopus"),
    ("fungus", "Phallus indusiatus"),
    ("fungus", "Tremella fuciformis"),
    ("fungus", "Galerina marginata"),
    ("fungus", "Agaricus bisporus"),
    ("fungus", "Calocybe indica"),
]

# Species that always contribute held-out photos, including the three the check must cover.
HELD_OUT_FOCUS = {
    "asplenium nidus", "monstera deliciosa", "dieffenbachia seguine",
    "epipremnum aureum", "zamioculcas zamiifolia", "chlorophytum comosum",
    "plumeria rubra", "hibiscus rosa-sinensis", "bougainvillea spectabilis",
    "codiaeum variegatum", "heliconia rostrata",
    "chlorophyllum molybdites", "volvariella volvacea", "pleurotus ostreatus",
    "amanita princeps", "schizophyllum commune", "psilocybe cubensis",
    "termitomyces eurrhizus", "lentinula edodes",
}

OPEN_LICENSES = {"cc0", "cc-by", "cc-by-sa"}
BANNED_HOSTS = ("pfaf.org", "theferns.info", "tropical.theferns.info")
SEA_BOX = {"swlat": "-11", "swlng": "92", "nelat": "21", "nelng": "142"}
SEA_TOKENS = {
    "ID", "MY", "SG", "TH", "PH", "VN", "KH", "LA", "MM",
    "JAW", "SUM", "LSI", "SUL", "MOL", "BOR", "NWG", "MLY", "THA", "PHI", "VIE", "CBD", "LAO",
}
SEA_WORDS = (
    "indonesia", "java", "jawa", "sumatra", "kalimantan", "sulawesi", "maluku", "papua",
    "malaysia", "thailand", "vietnam", "philippines", "singapore", "malesia", "cambodia", "laos",
    "myanmar",
)
VIEW_ORDER = {
    "houseplant": ("pot", "leaves", "flower", "habit", "fruit"),
    "garden": ("flower", "habit", "leaves", "fruit", "pot"),
    "fungus": ("cap", "gills", "stem", "habit"),
}
PHOTO_FIELDS = [
    "scientific_name", "category", "role", "view", "image_url", "license", "observer", "page",
    "stage", "source", "gbif_key",
]
TARGET_FIELDS = [
    "category", "query_name", "accepted_name", "gbif_key", "status", "basis", "citation",
    "existing_photos", "new_references", "heldout",
]
REF_CAP = 30          # existing pack photos plus new references
NEW_PER_OBS = 3
OBS_HELD_MOD = 5


def binomial(name: str) -> str:
    parts = [p for p in (name or "").replace("×", " ").replace("×", " ").split() if p.lower() not in ("x", "×")]
    return " ".join(parts[:2]).lower()


def banned(url: str | None) -> bool:
    low = (url or "").lower()
    return any(host in low for host in BANNED_HOSTS)


def pack_stage(view: str) -> str:
    return {"leaves": "leaves", "flower": "flower", "fruit": "fruit", "gills": "closeup", "stem": "closeup"}.get(view, "field")


def photo_aim(category: str, existing_n: int, have_views: set[str]) -> int:
    """How many new reference photos to add. The pack already counts toward the 10–30 band."""
    room = max(0, REF_CAP - existing_n)
    if category == "houseplant":
        aim = 8 if existing_n >= 10 else 18
    elif category == "fungus":
        if existing_n >= 12 and {"gills", "stem", "cap"} <= have_views:
            aim = 0
        else:
            aim = 8 if existing_n >= 10 else 16
    else:
        if existing_n >= 12 and {"habit", "flower"} <= have_views:
            aim = 0
        else:
            aim = 6 if existing_n else 16
    return min(room, aim)


def content_for_new_species(name: str, kingdom: str, genus: str | None, family: str | None,
                            category: str, curated: dict, rules: dict, quick: dict | None = None) -> dict:
    """Safety text for a species that was not already in the pack.

    Fungi never receive an edible verdict from this pass. Harm warnings that already exist
    (deadly Amanita, toxic Chlorophyllum, psychoactive Psilocybe) are kept, with any
    benefits or reported-effects text removed. Plants use the ordinary resolver.
    """
    from .content import finalize, group_content, resolve

    if category == "fungus" or (kingdom or "").lower() == "fungi":
        entry = curated.get(name.lower())
        edi = (entry or {}).get("edibility") or ""
        tox = (entry or {}).get("toxicity") or ""
        if entry and (edi in ("toxic", "deadly") or tox in ("serious", "deadly")):
            kept = finalize(entry)
            eaten = dict(kept.get("if_eaten") or {})
            eaten.pop("benefits", None)
            eaten.pop("feeling", None)
            kept["if_eaten"] = eaten
            kept.pop("feeling", None)
            if (kept.get("edibility") or "") in ("edible", "edible_with_preparation"):
                return finalize(None)
            return kept
        rule = rules.get(("genus", (genus or name.split()[0]).lower())) or rules.get(("family", (family or "").lower()))
        if rule and all(rule.get(k) for k in ("warning", "toxicity", "onset", "what_to_do", "source", "rank", "taxon")):
            return group_content(rule)
        return finalize(None)
    return finalize(resolve(curated, rules, name, genus, family, quick or {}))


def choose_references(cands: list[dict], *, category: str, existing_n: int, have_views: set[str],
                      existing_urls: set[str], heldout_goal: int) -> tuple[list[dict], list[dict]]:
    """Pick diverse reference photos and a disjoint held-out set.

    Open licences are used first. CC BY-NC fills a view only when an open photo of that view
    is missing. The same observation is not both a reference and a test photo.
    """
    aim = photo_aim(category, existing_n, have_views)
    if aim <= 0 and heldout_goal <= 0:
        return [], []
    seen = set(existing_urls)
    cleaned = []
    for raw in cands:
        url = (raw.get("url") or "").strip()
        page = (raw.get("page") or "").strip()
        lic = raw.get("license")
        if not url or not page or not (raw.get("observer") or "").strip():
            continue
        if url in seen or page in seen or banned(url) or banned(page):
            continue
        if lic not in config.PHOTO_LICENSES:
            continue
        seen.add(url)
        cleaned.append(raw)
    by_obs: dict[str, list[dict]] = defaultdict(list)
    for c in cleaned:
        by_obs[str(c.get("obs") or c["url"])].append(c)

    def hold_obs(obs: str) -> bool:
        digits = "".join(ch for ch in obs if ch.isdigit())
        return bool(digits) and int(digits) % OBS_HELD_MOD == 0

    hold_ids = {obs for obs in by_obs if hold_obs(obs)}
    ref_ids = {obs for obs in by_obs if obs not in hold_ids}

    def pick(obs_ids: set[str], limit: int, per_obs: int, blocked_nc: set[str] | None = None) -> list[dict]:
        blocked_nc = blocked_nc or set()
        buckets: dict[str, list[dict]] = defaultdict(list)
        for obs in obs_ids:
            for c in by_obs[obs]:
                buckets[c.get("view") or "habit"].append(c)
        for view, bucket in buckets.items():
            bucket.sort(key=lambda c: (c.get("license") not in OPEN_LICENSES, int(c.get("rank") or 5)))
        order = VIEW_ORDER.get(category, ("habit",))
        chosen: list[dict] = []
        per: dict[str, int] = defaultdict(int)
        who: dict[str, int] = defaultdict(int)
        while len(chosen) < limit:
            moved = False
            covered = {c.get("view") or "habit" for c in chosen if c.get("license") in OPEN_LICENSES}
            for view in order:
                if len(chosen) >= limit:
                    break
                bucket = buckets.get(view) or []
                keep = []
                took = False
                for c in bucket:
                    obs = str(c.get("obs") or c["url"])
                    observer = (c.get("observer") or "").strip().lower()
                    if took:
                        keep.append(c)
                        continue
                    # CC BY-NC is only used for a view that has no CC0 / CC BY / CC BY-SA photo.
                    if c.get("license") not in OPEN_LICENSES and (view in covered or view in blocked_nc):
                        continue
                    if per[obs] >= per_obs or (observer and who[observer] >= 4):
                        keep.append(c)
                        continue
                    chosen.append(c)
                    per[obs] += 1
                    who[observer] += 1
                    took = True
                    moved = True
                    if c.get("license") in OPEN_LICENSES:
                        covered.add(view)
                buckets[view] = keep
            if not moved:
                break
        return chosen

    held = pick(hold_ids, heldout_goal, per_obs=2) if heldout_goal else []
    used_hold = {str(c.get("obs") or c["url"]) for c in held}
    refs = pick(ref_ids - used_hold if ref_ids else set(), aim, per_obs=NEW_PER_OBS)
    if len(held) < heldout_goal:
        used_ref = {str(c.get("obs") or c["url"]) for c in refs}
        extra = {obs for obs in by_obs if obs not in used_ref and obs not in used_hold}
        held += pick(extra, heldout_goal - len(held), per_obs=2)
    if len(refs) < min(aim, 8):
        used_h = {str(c.get("obs") or c["url"]) for c in held}
        used_r = {str(c.get("obs") or c["url"]) for c in refs}
        more = {obs for obs in by_obs if obs not in used_h and obs not in used_r}
        covered_views = {r.get("view") or "habit" for r in refs if r.get("license") in OPEN_LICENSES}
        refs += pick(more, aim - len(refs), per_obs=NEW_PER_OBS, blocked_nc=covered_views)
    return refs[:aim], held[:heldout_goal]


def _sea_text(text: str) -> bool:
    """True when a GBIF locality names Southeast Asia.

    Tokens are whole words. A substring such as the final "LA" inside "ANGUILLA" is not Laos.
    """
    parts = re.findall(r"[A-Za-z]{2,}", text or "")
    if {p.upper() for p in parts} & SEA_TOKENS:
        return True
    low = " ".join(p.lower() for p in parts)
    return any(word in low for word in SEA_WORDS)


def cite_basis(key: int | None, name: str, category: str, cands: list[dict]) -> tuple[str, str]:
    """Why a missing name is added, and the page that supports it."""
    from .http import get_json

    page = f"https://www.gbif.org/species/{key}" if key else ""
    captive = next((c for c in cands if c.get("garden") and c.get("sea") and c.get("page")), None)
    if key:
        intro = []
        native = introduced = False
        try:
            dist = get_json(f"{config.GBIF_API}/species/{key}/distributions", {"limit": "300"})
            for row in dist.get("results") or []:
                means = (row.get("establishmentMeans") or "").upper()
                loc = " ".join(str(row.get(k) or "") for k in ("locality", "country", "locationId"))
                if not _sea_text(loc):
                    continue
                if means == "NATIVE":
                    native = True
                if means in ("INTRODUCED", "NATURALISED", "MANAGED"):
                    introduced = True
                    intro.append(f"{means} ({loc.strip()}; {row.get('source') or 'GBIF'})")
        except Exception as e:  # noqa: BLE001
            intro.append(f"distribution lookup failed ({e!r})")
        managed = introduced_n = 0
        try:
            managed = int(get_json(f"{config.GBIF_API}/occurrence/search", {
                "taxonKey": str(key), "country": "ID", "establishmentMeans": "MANAGED", "limit": "0",
            }).get("count") or 0)
            introduced_n = int(get_json(f"{config.GBIF_API}/occurrence/search", {
                "taxonKey": str(key), "country": "ID", "establishmentMeans": "INTRODUCED", "limit": "0",
            }).get("count") or 0)
        except Exception:  # noqa: BLE001
            pass
        if managed:
            url = f"https://www.gbif.org/occurrence/search?taxon_key={key}&country=ID&establishment_means=MANAGED"
            return "cultivated", f"GBIF records {managed} Indonesia occurrences with establishmentMeans=MANAGED. {url}"
        if introduced_n or introduced:
            url = f"https://www.gbif.org/occurrence/search?taxon_key={key}&country=ID&establishment_means=INTRODUCED"
            detail = "; ".join(intro[:3])
            return "introduced", (
                f"GBIF marks {name} introduced in Southeast Asia"
                + (f" ({detail})" if detail else "")
                + f". Indonesia INTRODUCED occurrences: {introduced_n}. {url} Species: {page}"
            )
        if native:
            return "recorded", (
                f"GBIF/WCVP distribution includes a native Southeast Asian locality, and the name was missing "
                f"from the pack. {page}"
            )
    if captive:
        return "cultivated", (
            f"Community-confirmed cultivated iNaturalist observation in Southeast Asia: {captive['page']}. "
            + (f"GBIF taxon: {page}" if page else f"Name: {name}.")
        )
    if category == "fungus":
        return "recorded", (
            f"Included so {name} can be told apart from similar fungi. No edibility is assessed. "
            + (page or "No GBIF key.")
        )
    return "cultivated", (
        f"{name} is not in the Indonesia pack. Included as a cultivated ornamental. "
        + (f"GBIF taxon: {page}" if page else "No GBIF species match.")
    )


def _fungus_view(index: int, n_photos: int, text: str) -> str:
    low = (text or "").lower()
    if any(w in low for w in ("gill", "lamella", "pore", "underside", "hymenophore")):
        return "gills"
    if any(w in low for w in ("stipe", "stem", "volva", "annulus", "ring")):
        return "stem"
    if any(w in low for w in ("cap", "pileus")):
        return "cap"
    if n_photos <= 1:
        return "habit"
    return ("cap", "gills", "stem", "cap")[min(index, 3)]


def _plant_view(stage: str, garden: bool) -> str:
    if stage == "leaves":
        return "leaves"
    if stage == "flower":
        return "flower"
    if stage == "fruit":
        return "fruit"
    if garden:
        return "pot"
    return "habit"


def parse_inat(data: dict, scientific: str, *, category: str, garden: bool, sea: bool, source: str) -> list[dict]:
    want = binomial(scientific)
    out = []
    for obs in data.get("results") or []:
        taxon = obs.get("taxon") or {}
        if binomial(taxon.get("name") or "") != want:
            continue
        if garden:
            try:
                agreed = int(obs.get("community_taxon_id") or 0) == int(taxon.get("id") or -1)
            except (TypeError, ValueError):
                agreed = False
            if not agreed:
                continue
        elif obs.get("quality_grade") != "research":
            continue
        user = obs.get("user") or {}
        observer = (user.get("name") or user.get("login") or "").strip()
        photos = [p for p in (obs.get("photos") or []) if p.get("url")][:4]
        ann = ""
        for a in obs.get("annotations") or []:
            if a.get("controlled_attribute_id") == 12:
                ann = {13: "flower", 15: "flower", 14: "fruit", 21: "leaves"}.get(a.get("controlled_value_id"), "")
        for i, ph in enumerate(photos):
            lic = config.photo_license(ph.get("license_code"))
            url = (ph.get("url") or "").replace("/square.", "/medium.").replace("/small.", "/medium.")
            if lic is None or not url or not observer or banned(url):
                continue
            if category == "fungus":
                view = _fungus_view(i, len(photos), "")
            else:
                view = _plant_view(ann or "field", garden)
            out.append({
                "url": url, "page": f"https://www.inaturalist.org/photos/{ph.get('id')}",
                "license": lic, "observer": observer[:200], "view": view, "stage": pack_stage(view),
                "source": source, "obs": str(obs.get("id")), "garden": garden, "sea": sea, "rank": 1 if sea else 2,
            })
    return out


def fetch_inat(scientific: str, category: str) -> list[dict]:
    from .http import MIN_INTERVAL, get_json

    MIN_INTERVAL["api.inaturalist.org"] = float(__import__("os").environ.get("OW_INAT_INTERVAL") or 1.1)
    open_lic = "cc0,cc-by,cc-by-sa"
    nc_lic = "cc0,cc-by,cc-by-sa,cc-by-nc,cc-by-nc-sa"
    calls = []
    for sea in (True, False):
        for garden in (False, True):
            calls.append((sea, garden, open_lic, "inaturalist" + ("-sea" if sea else "") + ("-garden" if garden else "")))
    out: list[dict] = []
    for sea, garden, lic, source in calls:
        if len(out) >= 48 and sum(1 for c in out if c["license"] in OPEN_LICENSES) >= 16:
            break
        params = {
            "taxon_name": scientific, "photos": "true", "photo_license": lic, "identified": "true",
            "per_page": "40", "order_by": "votes", "captive": "true" if garden else "false",
        }
        if not garden:
            params["quality_grade"] = "research"
        if sea:
            params.update(SEA_BOX)
        try:
            data = get_json("https://api.inaturalist.org/v1/observations", params, retries=4, timeout=45)
        except Exception as e:  # noqa: BLE001
            print(f"inat {scientific} {source} failed: {e!r}", flush=True)
            continue
        out.extend(parse_inat(data, scientific, category=category, garden=garden, sea=sea, source=source))
    if sum(1 for c in out if c["license"] in OPEN_LICENSES) < 10:
        params = {
            "taxon_name": scientific, "photos": "true", "photo_license": nc_lic, "identified": "true",
            "per_page": "30", "order_by": "votes", "captive": "true",
        }
        try:
            data = get_json("https://api.inaturalist.org/v1/observations", params, retries=3, timeout=45)
            out.extend(parse_inat(data, scientific, category=category, garden=True, sea=False, source="inaturalist-garden-nc"))
        except Exception as e:  # noqa: BLE001
            print(f"inat nc {scientific} failed: {e!r}", flush=True)
    return out


def fetch_commons(scientific: str, category: str) -> list[dict]:
    from .commercial_photos import from_commons_search

    out = []
    for cand in from_commons_search(scientific, limit=15):
        if banned(cand.get("url")) or banned(cand.get("page")):
            continue
        text = f"{cand.get('page') or ''} {cand.get('url') or ''}"
        if category == "fungus":
            view = _fungus_view(0, 1, text)
        else:
            view = _plant_view(cand.get("stage") or "field", False)
        out.append({
            "url": cand["url"], "page": cand["page"], "license": cand["license"],
            "observer": (cand.get("observer") or "").strip()[:200], "view": view, "stage": pack_stage(view),
            "source": "wikimedia", "obs": cand["page"], "garden": False, "sea": False, "rank": 3,
        })
    return [c for c in out if c["observer"]]


def fetch_gbif(key: int, scientific: str, category: str) -> list[dict]:
    from .commercial_photos import is_inaturalist, stage_from_gbif
    from .http import get_json

    licenses = ["CC0_1_0", "CC_BY_4_0", "CC_BY_SA_4_0", "CC_BY_3_0", "CC_BY_SA_3_0", "CC_BY_2_0"]
    try:
        data = get_json(f"{config.GBIF_API}/occurrence/search", {
            "taxonKey": str(key), "mediaType": "StillImage", "limit": "40", "license": licenses,
        }, retries=3)
    except Exception as e:  # noqa: BLE001
        print(f"gbif media {scientific} failed: {e!r}", flush=True)
        return []
    out = []
    for occ in data.get("results") or []:
        if occ.get("basisOfRecord") == "PRESERVED_SPECIMEN":
            continue
        if is_inaturalist(occ.get("references"), occ.get("occurrenceID"), occ.get("publisher")):
            continue
        stage = stage_from_gbif(occ)
        if stage == "specimen":
            continue
        country = occ.get("country") or ""
        sea = country in SEA_TOKENS or _sea_text(occ.get("country") or "")
        for media in (occ.get("media") or [])[:2]:
            url = media.get("identifier") or ""
            page = media.get("references") or f"https://www.gbif.org/occurrence/{occ.get('key')}"
            lic = config.photo_license(media.get("license")) or config.photo_license(occ.get("license"))
            observer = (media.get("rightsHolder") or media.get("creator") or occ.get("recordedBy") or "").strip()
            if not url or not lic or not observer or banned(url) or banned(page) or is_inaturalist(url, page):
                continue
            if category == "fungus":
                view = _fungus_view(0, 1, url + " " + page)
            else:
                view = _plant_view(stage, False)
            out.append({
                "url": url, "page": page, "license": lic, "observer": observer[:200], "view": view,
                "stage": pack_stage(view), "source": "gbif", "obs": f"gbif:{occ.get('key')}",
                "garden": False, "sea": sea, "rank": 3,
            })
    return out


def resolve_name(query: str, category: str) -> dict | None:
    from .gbif import species_info, vernacular_names
    from .http import get_json

    kingdom = "Fungi" if category == "fungus" else "Plantae"
    try:
        match = get_json(f"{config.GBIF_API}/species/match", {"name": query, "kingdom": kingdom}, retries=3)
    except Exception as e:  # noqa: BLE001
        print(f"match {query} failed: {e!r}", flush=True)
        return None
    if match.get("matchType") == "NONE" or not match.get("usageKey"):
        return None
    key = int(match.get("acceptedUsageKey") or match.get("usageKey"))
    try:
        info = species_info(key)
    except Exception as e:  # noqa: BLE001
        print(f"species {key} failed: {e!r}", flush=True)
        return None
    rank = (match.get("rank") or "").upper()
    if rank and rank not in ("SPECIES", "VARIETY", "SUBSPECIES", "FORM"):
        return None
    if not info.get("scientific_name"):
        return None
    names = {}
    try:
        names = vernacular_names(key)
    except Exception:  # noqa: BLE001
        names = {}
    info["common_names"] = names
    info["common_name"] = names.get("eng") or names.get("ind") or info.get("common_name") or ""
    info["kingdom"] = info.get("kingdom") or kingdom
    return info


def load_pack_index(pack: Path) -> dict:
    con = sqlite3.connect(pack)
    species = {}
    for sid, name, kingdom, family, genus in con.execute(
        "SELECT id, scientific_name, kingdom, family, genus FROM species"
    ):
        species[name.lower()] = {"id": sid, "name": name, "kingdom": kingdom, "family": family, "genus": genus}
    photos = dict(con.execute("SELECT species_id, COUNT(*) FROM photo GROUP BY species_id"))
    stages: dict[int, set[str]] = defaultdict(set)
    for sid, stage in con.execute("SELECT species_id, stage FROM photo"):
        stages[sid].add(stage or "field")
    urls: dict[int, set[str]] = defaultdict(set)
    for sid, url in con.execute("SELECT species_id, url FROM photo"):
        if url:
            urls[sid].add(url)
    for sid, url in con.execute("SELECT species_id, url FROM attribution"):
        if url:
            urls[sid].add(url)
    meta = dict(con.execute("SELECT key, value FROM meta"))
    con.close()
    return {"species": species, "photos": photos, "stages": stages, "urls": urls, "meta": meta}


def views_from_stages(stages: set[str], category: str) -> set[str]:
    out = set()
    for stage in stages:
        if stage in ("field", "unspecified"):
            out.add("habit")
        elif stage == "leaves":
            out.add("leaves")
        elif stage == "flower":
            out.add("flower")
        elif stage == "fruit":
            out.add("fruit")
        elif stage == "closeup" and category == "fungus":
            out.add("cap")
    return out


def dhash(path: Path) -> int | None:
    from PIL import Image

    try:
        im = Image.open(path).convert("L").resize((9, 8))
    except Exception:  # noqa: BLE001
        return None
    px = list(im.getdata())
    bits = 0
    for y in range(8):
        row = y * 9
        for x in range(8):
            bits = (bits << 1) | (1 if px[row + x] > px[row + x + 1] else 0)
    return bits


def near(a: int, b: int, limit: int = 6) -> bool:
    return (a ^ b).bit_count() <= limit


def download_rows(rows: list[dict], img_dir: Path) -> list[dict]:
    import hashlib

    from .commercial_photos import _download, looks_like_sheet

    kept = []
    hashes: dict[str, list[int]] = defaultdict(list)
    for row in rows:
        url = row.get("url") or row.get("image_url") or ""
        if not url:
            continue
        digest = hashlib.sha1(url.encode()).hexdigest()[:20]
        dest = img_dir / f"{digest}.img"
        if not _download(url, dest):
            continue
        if looks_like_sheet(dest, row.get("page"), url):
            continue
        name = (row.get("scientific_name") or "").lower()
        digest_bits = dhash(dest)
        if digest_bits is not None and any(near(digest_bits, prev) for prev in hashes[name]):
            continue
        if digest_bits is not None:
            hashes[name].append(digest_bits)
        kept.append({**row, "path": str(dest), "url": url})
    return kept


def leave_one_out_matrix(labels: list[str], embs: np.ndarray, batch: int = 512) -> dict:
    """Same leave-one-out as evaluate.leave_one_out, scored in matrix batches."""
    from .evaluate import leave_one_out

    labels = [l.strip() for l in labels]
    if len(labels) < 800:
        return leave_one_out(labels, embs)
    species = sorted(set(labels))
    idx = {s: i for i, s in enumerate(species)}
    y = np.array([idx[l] for l in labels], dtype=np.int32)
    e = embs.astype(np.float32)
    e /= np.maximum(np.linalg.norm(e, axis=1, keepdims=True), 1e-12)
    counts = np.bincount(y, minlength=len(species))
    qidx = np.nonzero(counts[y] >= 2)[0]
    ranks = np.empty(len(qidx), np.int32)
    confused: Counter = Counter()
    per: dict[str, list[int]] = defaultdict(list)
    for start in range(0, len(qidx), batch):
        qs = qidx[start:start + batch]
        sims = e @ e[qs].T
        for j, q in enumerate(qs):
            sims[q, j] = -np.inf
        best = np.full((len(species), len(qs)), -np.inf, np.float32)
        for j in range(len(qs)):
            np.maximum.at(best[:, j], y, sims[:, j])
        order = np.argsort(-best, axis=0)
        true = y[qs]
        for j in range(len(qs)):
            rank = int(np.nonzero(order[:, j] == true[j])[0][0]) + 1
            ranks[start + j] = rank
            per[labels[qs[j]]].append(rank)
            if rank > 1:
                confused[(labels[qs[j]], species[int(order[0, j])])] += 1
        if start and start % (batch * 20) == 0:
            print(f"loo {start}/{len(qidx)}", flush=True)
    n = len(ranks)
    return {
        "photos_tested": int(n),
        "species_tested": int(len(per)),
        "species_total": int(len(species)),
        "top_k": {f"top{k}": round(float((ranks <= k).mean()), 4) if n else None for k in (1, 3, 5, 10)},
        "median_rank": float(np.median(ranks)) if n else None,
        "most_confused": [{"true": a, "shown_first": b, "times": c} for (a, b), c in confused.most_common(40)],
        "hardest_species": sorted(
            ({"species": s, "photos": len(r), "top1": round(float(np.mean(np.array(r) == 1)), 3)}
             for s, r in per.items() if len(r) >= 3),
            key=lambda d: (d["top1"], -d["photos"]))[:40],
    }


def prototypes_of(pack: Path) -> tuple[list[str], np.ndarray]:
    con = sqlite3.connect(pack)
    rows = con.execute(
        "SELECT s.scientific_name, p.vec, p.scale FROM prototype p "
        "JOIN species s ON s.id = p.species_id WHERE p.kind = 'image'"
    ).fetchall()
    con.close()
    labels = [r[0] for r in rows]
    embs = np.zeros((len(rows), config.EMBED_DIM), np.float32)
    for i, (_, blob, scale) in enumerate(rows):
        embs[i] = np.frombuffer(blob, dtype=np.int8).astype(np.float32) * float(scale)
    return labels, embs


def score_heldout(labels: list[str], embs: np.ndarray, queries: np.ndarray, query_labels: list[str]) -> dict:
    from .prototypes import l2n

    e = l2n(embs.astype(np.float32))
    q = l2n(queries.astype(np.float32))
    species = sorted(set(labels))
    idx = {s: i for i, s in enumerate(species)}
    y = np.array([idx[l] for l in labels], dtype=np.int32)
    sims = e @ q.T
    hits = []
    for j, true_name in enumerate(query_labels):
        best = np.full(len(species), -np.inf, np.float32)
        np.maximum.at(best, y, sims[:, j])
        order = np.argsort(-best)
        ranked = [species[i] for i in order[:3]]
        if true_name not in idx:
            rank = None
        else:
            rank = int(np.nonzero(order == idx[true_name])[0][0]) + 1
        hits.append({"species": true_name, "rank": rank, "top3": ranked})
    known = [h for h in hits if h["rank"] is not None]
    n = len(known)
    return {
        "tested": n,
        "missing_species": len(hits) - n,
        "top1": round(sum(h["rank"] == 1 for h in known) / n, 4) if n else None,
        "top3": round(sum(h["rank"] is not None and h["rank"] <= 3 for h in known) / n, 4) if n else None,
        "rows": hits,
    }


def augment_pack(base: Path, out: Path, additions: list[dict], version: int) -> dict:
    """Copy `base` and add reference photos plus one DINOv2 prototype each.

    Existing species, photos and prototypes stay. A new fungus does not gain an edibility claim.
    """
    from .content import load_curated, load_group_rules, load_quick
    from .pack import encode_photo, months_blob
    from .prototypes import quantize

    out.parent.mkdir(parents=True, exist_ok=True)
    if out.resolve() != base.resolve():
        shutil.copyfile(base, out)
    curated, rules, quick = load_curated(), load_group_rules(), load_quick()
    con = sqlite3.connect(out)
    meta = dict(con.execute("SELECT key, value FROM meta"))
    if meta.get("model_id") != config.MODEL_ID:
        con.close()
        raise RuntimeError(f"pack model {meta.get('model_id')} != {config.MODEL_ID}")
    name_to_id = {n.lower(): i for i, n in con.execute("SELECT id, scientific_name FROM species")}
    next_id = con.execute("SELECT COALESCE(MAX(id), 0) FROM species").fetchone()[0]
    added_species = added_photos = added_proto = 0
    by_name: dict[str, list[dict]] = defaultdict(list)
    for row in additions:
        by_name[row["scientific_name"].lower()].append(row)
    for key, rows in by_name.items():
        rows = [r for r in rows if r.get("path") and Path(r["path"]).exists()]
        if not rows:
            continue
        info = rows[0]
        if key in name_to_id:
            sid = name_to_id[key]
        else:
            if len(rows) < 2:
                continue
            next_id += 1
            sid = next_id
            name_to_id[key] = sid
            genus = info.get("genus") or info["scientific_name"].split()[0]
            content = content_for_new_species(
                info["scientific_name"], info.get("kingdom") or "Plantae", genus, info.get("family"),
                info.get("category") or "garden", curated, rules, quick,
            )
            names = info.get("common_names") or {}
            if isinstance(names, str):
                try:
                    names = json.loads(names) if names else {}
                except json.JSONDecodeError:
                    names = {}
            con.execute(
                "INSERT INTO species VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    sid, None, int(info["gbif_key"]) if str(info.get("gbif_key") or "").isdigit() else None,
                    info["scientific_name"], info.get("common_name") or None,
                    json.dumps(names) if names else None,
                    info.get("kingdom") or "Plantae", info.get("family") or None, genus,
                    int(info.get("gbif_count") or 0), months_blob([0] * 12),
                    info.get("description"), info.get("wiki_url"),
                ),
            )
            con.execute("INSERT INTO content VALUES (?, ?)", (sid, json.dumps(content)))
            added_species += 1
        have = {u for (u,) in con.execute("SELECT url FROM photo WHERE species_id = ?", (sid,))}
        have |= {u for (u,) in con.execute("SELECT url FROM attribution WHERE species_id = ?", (sid,))}
        rank = con.execute("SELECT COALESCE(MAX(rank), -1) FROM photo WHERE species_id = ?", (sid,)).fetchone()[0]
        shown = con.execute("SELECT COUNT(*) FROM photo WHERE species_id = ?", (sid,)).fetchone()[0]
        for row in rows:
            url = row["page"]
            if url in have or banned(url) or banned(row.get("url")):
                continue
            lic = config.photo_license(row.get("license"))
            if lic is None:
                continue
            blob, scale = quantize(_unit(row["embedding"]))
            con.execute(
                "INSERT INTO prototype (species_id, vec, scale, kind) VALUES (?,?,?,'image')",
                (sid, blob, scale),
            )
            added_proto += 1
            if shown < 8:
                enc = encode_photo(row["path"])
                if enc is not None:
                    rank += 1
                    shown += 1
                    con.execute(
                        "INSERT INTO photo (species_id, rank, thumb, image, observer, license, url, stage) "
                        "VALUES (?,?,?,?,?,?,?,?)",
                        (sid, rank, enc[0], enc[1], row.get("observer"), lic, url, row.get("stage") or "field"),
                    )
                    added_photos += 1
            con.execute(
                "INSERT INTO attribution (species_id, observer, license, url) VALUES (?,?,?,?)",
                (sid, row.get("observer"), lic, url),
            )
            have.add(url)
    sources = json.loads(meta.get("sources") or "[]")
    note = "Houseplant, garden and fungus reference photos (CC0, CC BY, CC BY-SA, CC BY-NC where the non-commercial policy allows)"
    if note not in sources:
        sources.append(note)
    con.execute("UPDATE meta SET value = ? WHERE key = 'sources'", (json.dumps(sources),))
    con.execute("UPDATE meta SET value = ? WHERE key = 'pack_version'", (str(version),))
    from datetime import datetime, timezone
    con.execute(
        "UPDATE meta SET value = ? WHERE key = 'built_at'",
        (datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),),
    )
    con.commit()
    con.execute("VACUUM")
    con.close()
    return {"added_species": added_species, "added_photos": added_photos, "added_prototypes": added_proto}


def _unit(vec: np.ndarray) -> np.ndarray:
    v = np.asarray(vec, dtype=np.float32)
    return v / (np.linalg.norm(v) + 1e-12)


def _write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in fields})


def _description(category: str, name: str) -> str:
    if category == "fungus":
        return f"{name} is included so it can be recognised. No edibility is assessed here; treat it as unsafe to eat."
    if category == "houseplant":
        return f"{name} is included as a houseplant or indoor ornamental."
    return f"{name} is included as a tropical garden or landscape plant."


def collect_targets(pack_index: dict, hidden: set[str]) -> tuple[list[dict], list[dict], list[dict]]:
    """Resolve names, fetch photos, and return target rows, reference rows and held-out rows."""
    species = pack_index["species"]
    seen_accepted: set[str] = set()
    targets, refs, held = [], [], []
    hidden_obs = set(re.findall(r"observations/(\d+)", " ".join(hidden)))
    for category, query in TARGETS:
        info = None
        accepted = query
        key = ""
        in_pack = query.lower() in species
        if not in_pack:
            info = resolve_name(query, category)
            if info and info.get("scientific_name"):
                accepted = info["scientific_name"]
                key = str(info.get("gbif_key") or "")
                in_pack = accepted.lower() in species
        else:
            accepted = species[query.lower()]["name"]
        if accepted.lower() in seen_accepted and query.lower() != accepted.lower():
            targets.append({
                "category": category, "query_name": query, "accepted_name": accepted, "gbif_key": key,
                "status": "synonym", "basis": "", "citation": f"Accepted name {accepted} is already listed.",
                "existing_photos": "", "new_references": 0, "heldout": 0,
            })
            continue
        seen_accepted.add(accepted.lower())
        row_sp = species.get(accepted.lower())
        existing_n = pack_index["photos"].get(row_sp["id"], 0) if row_sp else 0
        have_views = views_from_stages(pack_index["stages"].get(row_sp["id"], set()), category) if row_sp else set()
        existing_urls = set(pack_index["urls"].get(row_sp["id"], set())) if row_sp else set()
        held_goal = 5 if accepted.lower() in ("asplenium nidus", "monstera deliciosa", "dieffenbachia seguine") else (
            4 if accepted.lower() in HELD_OUT_FOCUS or not in_pack else 0
        )
        aim = photo_aim(category, existing_n, have_views)
        if aim <= 0 and held_goal <= 0:
            targets.append({
                "category": category, "query_name": query, "accepted_name": accepted, "gbif_key": key,
                "status": "already_covered", "basis": "", "citation": "",
                "existing_photos": existing_n, "new_references": 0, "heldout": 0,
            })
            continue
        if not key and row_sp is None:
            info = info or resolve_name(query, category)
            if info:
                accepted = info["scientific_name"]
                key = str(info.get("gbif_key") or "")
        print(f"FETCH {accepted} ({category}) existing={existing_n} aim={aim} held={held_goal}", flush=True)
        cands = fetch_inat(accepted, category)
        if sum(1 for c in cands if c["license"] in OPEN_LICENSES) < 12:
            cands.extend(fetch_commons(accepted, category))
        if key and sum(1 for c in cands if c["license"] in OPEN_LICENSES) < 12:
            cands.extend(fetch_gbif(int(key), accepted, category))
        cands = [
            c for c in cands
            if str(c.get("obs")) not in hidden_obs and c.get("page") not in hidden and c.get("url") not in hidden
            and not banned(c.get("page")) and not banned(c.get("url"))
        ]
        chosen, held_rows = choose_references(
            cands, category=category, existing_n=existing_n, have_views=have_views,
            existing_urls=existing_urls, heldout_goal=held_goal,
        )
        basis = citation = ""
        status = "topped_up" if in_pack else "added"
        if not in_pack:
            if info is None:
                info = resolve_name(accepted, category)
                if info:
                    key = str(info.get("gbif_key") or key)
                    accepted = info["scientific_name"]
            basis, citation = cite_basis(int(key) if str(key).isdigit() else None, accepted, category, cands)
            if len(chosen) < 2:
                status = "too_few_photos"
        for role, group in (("reference", chosen), ("heldout", held_rows)):
            for c in group:
                item = {
                    "scientific_name": accepted, "category": category, "role": role, "view": c["view"],
                    "image_url": c["url"], "license": c["license"], "observer": c["observer"], "page": c["page"],
                    "stage": c["stage"], "source": c["source"], "gbif_key": key,
                    "kingdom": (info or {}).get("kingdom") or ("Fungi" if category == "fungus" else "Plantae"),
                    "family": (info or row_sp or {}).get("family") or "",
                    "genus": (info or row_sp or {}).get("genus") or accepted.split()[0],
                    "common_name": (info or {}).get("common_name") or "",
                    "common_names": json.dumps((info or {}).get("common_names") or {}, ensure_ascii=False),
                    "basis": basis, "citation": citation, "description": _description(category, accepted),
                    "wiki_url": f"https://www.gbif.org/species/{key}" if key else "",
                    "gbif_count": "0",
                }
                (refs if role == "reference" else held).append(item)
        targets.append({
            "category": category, "query_name": query, "accepted_name": accepted, "gbif_key": key,
            "status": status, "basis": basis, "citation": citation,
            "existing_photos": existing_n, "new_references": len(chosen), "heldout": len(held_rows),
        })
    return targets, refs, held


def build_reference_pack(region_id: str, base: Path, out: Path | None = None, version: int | None = None) -> dict:
    from datetime import datetime, timezone

    from .evaluate import write_report
    from .freemodel import embed_tflite
    from .inbox import hidden_photos
    from .pack import sha256, update_index
    from .regions import merged_donors

    base = Path(base)
    if version is None:
        version = int(datetime.now(timezone.utc).strftime("%Y%m%d%H"))
    meta_con = sqlite3.connect(base)
    published = int(dict(meta_con.execute("SELECT key, value FROM meta"))["pack_version"])
    meta_con.close()
    if version <= published:
        version = published + 1
    data = config.DATA_REGIONS / region_id
    work = config.WORK / "references" / region_id
    img = work / "img"
    img.mkdir(parents=True, exist_ok=True)
    print("leave-one-out before", flush=True)
    before_labels, before_embs = prototypes_of(base)
    before = leave_one_out_matrix(before_labels, before_embs)
    (data / "eval").mkdir(parents=True, exist_ok=True)
    (data / "eval" / "before_accuracy.json").write_text(json.dumps(
        {k: v for k, v in before.items() if k != "focus"}, indent=1))
    print("before", before["top_k"], flush=True)

    hidden: set[str] = set()
    for rid in (region_id, *merged_donors(region_id)):
        hidden |= hidden_photos(rid)
    index = load_pack_index(base)
    targets, refs, held = collect_targets(index, hidden)
    print(f"downloading {len(refs)} references and {len(held)} held-out", flush=True)
    ref_ok = download_rows(refs, img)
    # Held-out images must not be near-duplicates of a reference of the same species.
    ref_hash: dict[str, list[int]] = defaultdict(list)
    for row in ref_ok:
        bits = dhash(Path(row["path"]))
        if bits is not None:
            ref_hash[row["scientific_name"].lower()].append(bits)
    held_ok = []
    for row in download_rows(held, img):
        bits = dhash(Path(row["path"]))
        prev = ref_hash[row["scientific_name"].lower()]
        if bits is not None and any(near(bits, p, limit=8) for p in prev):
            continue
        held_ok.append(row)
    from .freemodel import preprocess

    def readable(rows: list[dict]) -> list[dict]:
        out_rows = []
        for row in rows:
            try:
                preprocess(Path(row["path"]))
            except Exception as e:  # noqa: BLE001
                print(f"skip unreadable {row.get('scientific_name')}: {e!r}", flush=True)
                continue
            out_rows.append(row)
        return out_rows

    ref_ok, held_ok = readable(ref_ok), readable(held_ok)
    kept_n = Counter(r["scientific_name"] for r in ref_ok)
    for t in targets:
        if t["status"] == "added" and kept_n[t["accepted_name"]] < 2:
            t["status"] = "too_few_photos"
    added_names = {t["accepted_name"] for t in targets if t["status"] == "added"}
    _write_csv(data / "reference_targets.csv", targets, TARGET_FIELDS)
    _write_csv(data / "reference_photos.csv", refs + held, PHOTO_FIELDS)
    cultivated = []
    seen_c: set[str] = set()
    for row in ref_ok:
        if row["scientific_name"] not in added_names or row["scientific_name"].lower() in seen_c:
            continue
        if not (row.get("citation") or "").strip():
            continue
        seen_c.add(row["scientific_name"].lower())
        cultivated.append({
            "gbif_key": row.get("gbif_key") or "",
            "scientific_name": row["scientific_name"],
            "kingdom": row.get("kingdom") or "Plantae",
            "family": row.get("family") or "",
            "genus": row.get("genus") or "",
            "common_name": row.get("common_name") or "",
            "common_names": row.get("common_names") or "",
            "gbif_count": row.get("gbif_count") or "0",
            "basis": row.get("basis") or "cultivated",
            "citation": row.get("citation") or "",
        })
    _write_csv(cultivated_path(region_id), cultivated, CULTIVATED_FIELDS)
    print(f"kept {len(ref_ok)} references, {len(held_ok)} held-out", flush=True)

    model = config.ANDROID_ASSETS / "embedder.tflite"
    paths = [Path(r["path"]) for r in ref_ok + held_ok]
    vecs = embed_tflite(paths, model) if paths else np.zeros((0, config.EMBED_DIM), np.float32)
    for row, vec in zip(ref_ok, vecs[:len(ref_ok)]):
        row["embedding"] = vec
    additions = []
    for row in ref_ok:
        if row["scientific_name"] in added_names:
            # New species need the taxonomy carried on the photo row.
            additions.append(row)
        else:
            additions.append(row)
    dest = out or (config.PACKS_DIR / f"{region_id}-v{version}.owpack")
    stats = augment_pack(base, dest, additions, version)
    from .deadly import insert_into_pack

    deadly_stats = insert_into_pack(dest)
    stats = {**stats, "deadly": deadly_stats}
    print("augment", stats, flush=True)

    print("leave-one-out after", flush=True)
    after_labels, after_embs = prototypes_of(dest)
    after = leave_one_out_matrix(after_labels, after_embs)
    write_report({k: v for k, v in after.items() if k != "focus"}, data / "eval", region_id)
    (data / "eval" / "before_accuracy.json").write_text(json.dumps(
        {k: v for k, v in before.items() if k != "focus"}, indent=1))

    held_report = {"tested": 0, "top1": None, "top3": None, "rows": []}
    if held_ok:
        held_q = vecs[len(ref_ok):len(ref_ok) + len(held_ok)]
        # Score the same photos against the published prototypes and the candidate prototypes.
        before_h = score_heldout(before_labels, before_embs, held_q, [r["scientific_name"] for r in held_ok])
        after_h = score_heldout(after_labels, after_embs, held_q, [r["scientific_name"] for r in held_ok])
        held_report = {"before": before_h, "after": after_h}
    (data / "eval" / "heldout.json").write_text(json.dumps(held_report, indent=1))

    digest = sha256(dest)
    index_path = dest.parent / "candidate-index.json"
    # Start from the published index so the entry sits beside the other regions.
    # This file stays on disk. It is not uploaded to the packs release.
    published_index = Path("/tmp/index.json")
    if published_index.exists():
        shutil.copyfile(published_index, index_path)
    update_index(
        index_path, dest,
        f"https://github.com/zenv99/openwild/releases/download/packs/{region_id}.owpack",
    )
    summary = {
        "path": str(dest),
        "size": dest.stat().st_size,
        "sha256": digest,
        "version": version,
        "published_version": published,
        "augment": stats,
        "before": before["top_k"],
        "after": after["top_k"],
        "before_photos": before["photos_tested"],
        "after_photos": after["photos_tested"],
        "heldout": {
            "before": {k: held_report.get("before", {}).get(k) for k in ("tested", "top1", "top3")},
            "after": {k: held_report.get("after", {}).get(k) for k in ("tested", "top1", "top3")},
        },
        "bioclip": "not built: no Indonesia boost is published, and a partial BioCLIP file would replace recognition for every other species",
    }
    (data / "eval" / "candidate_pack.json").write_text(json.dumps(summary, indent=1))
    _write_reference_report(data / "eval" / "REFERENCES.md", targets, ref_ok, held_ok, summary, before, after, held_report)
    print(json.dumps({k: summary[k] for k in ("path", "size", "sha256", "version", "before", "after")}, indent=1))
    return summary


def _pct(v) -> str:
    return "n/a" if v is None else f"{v * 100:.1f}%"


def _write_reference_report(path: Path, targets, refs, held, summary, before, after, held_report) -> None:
    by_cat = Counter(r["category"] for r in refs)
    by_lic = Counter(r["license"] for r in refs)
    by_cat_lic = Counter((r["category"], r["license"]) for r in refs)
    added = [t for t in targets if t["status"] == "added"]
    lines = [
        "# Indonesia reference photos",
        "",
        "Candidate pack only. It is not on the packs release.",
        "",
        f"- Path: `{summary['path']}`",
        f"- Size: {summary['size']} bytes",
        f"- SHA-256: `{summary['sha256']}`",
        f"- Version: {summary['version']} (published pack was {summary['published_version']})",
        f"- Species added: {summary['augment']['added_species']}",
        f"- Display photos added: {summary['augment']['added_photos']}",
        f"- Prototypes added: {summary['augment']['added_prototypes']}",
        "",
        "## Leave-one-out on the whole pack",
        "",
        "Each image prototype is matched with itself hidden. This is the fingerprint the phone stores.",
        "",
        f"| | Photos | Species | Top-1 | Top-3 |",
        f"|---|---:|---:|---:|---:|",
        f"| Published | {before['photos_tested']} | {before['species_tested']} | {_pct(before['top_k'].get('top1'))} | {_pct(before['top_k'].get('top3'))} |",
        f"| Candidate | {after['photos_tested']} | {after['species_tested']} | {_pct(after['top_k'].get('top1'))} | {_pct(after['top_k'].get('top3'))} |",
        "",
        "## Held-out photos",
        "",
        "These files were not packed as references.",
        "",
    ]
    for label in ("before", "after"):
        block = held_report.get(label) or {}
        lines.append(f"- Against the {label} pack: {_pct(block.get('top1'))} top-1, {_pct(block.get('top3'))} top-3, n={block.get('tested')}")
    lines += ["", "## Reference photos by category and licence", ""]
    for cat in ("houseplant", "garden", "fungus"):
        lines.append(f"- {cat}: {by_cat[cat]} photos")
        for lic, n in sorted(by_lic.items()):
            if by_cat_lic[(cat, lic)]:
                lines.append(f"  - {lic}: {by_cat_lic[(cat, lic)]}")
    lines += ["", "Licence totals (references only):"]
    for lic, n in sorted(by_lic.items(), key=lambda kv: -kv[1]):
        lines.append(f"- {lic}: {n}")
    lines += ["", f"Held-out files kept: {len(held)}", "", "## Species added", ""]
    for t in added:
        lines.append(f"- {t['accepted_name']} ({t['category']}, {t['basis']}): {t['citation']}")
    lines += ["", "## Names considered", ""]
    for t in targets:
        if t["status"] == "synonym":
            continue
        lines.append(
            f"- {t['accepted_name']} [{t['category']}] {t['status']}: "
            f"already {t['existing_photos']}, new {t['new_references']}, held-out {t['heldout']}"
        )
    lines += [
        "",
        "## BioCLIP 2",
        "",
        summary["bioclip"],
        "",
        "DINOv2 ViT-S/14 (`ow-dinov2s-384-v1`) fingerprints were computed with the same LiteRT file the phone runs.",
        "",
    ]
    path.write_text("\n".join(lines) + "\n")
