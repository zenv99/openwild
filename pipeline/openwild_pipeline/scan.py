"""OpenWild online check: a slower, more careful identification that runs in the cloud (GitHub Actions in the
private inbox repo), only for people who opt in.

Two passes, the way the big identification services work:
  1. Shortlist: full-precision BioCLIP 2 compares the user's photos against every plant and fungus known for the
     whole country (by name) and every reference photo of the region (by picture). Keeps the best ~15.
  2. Verify: for each shortlisted species, fetch dozens of fresh community-confirmed iNaturalist photos (wild
     AND garden/potted) and compare the user's photos against each one. The species whose real photos look most
     like the user's wins. Slow (minutes) but far more reliable than a single fingerprint match.

The phone writes scans/<id>/meta.json + N.jpg into the inbox; this module writes scans/<id>/result.json and,
unless the user agreed to share, deletes the photos.
"""
from __future__ import annotations

import base64
import io
import json
import os
import sqlite3
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

HUB = "hf-hub:imageomics/bioclip-2"
PACKS = "https://github.com/zenv99/openwild/releases/download/packs/"
INAT = "https://api.inaturalist.org/v1/observations"
LICENSES = "cc0,cc-by,cc-by-sa,cc-by-nc,cc-by-nc-sa"
UA = {"User-Agent": "OpenWild-online-check/1.0 (+https://github.com/zenv99/openwild)"}
SHORTLIST = int(os.environ.get("OW_SCAN_SHORTLIST", "15"))
WILD, GARDEN = 24, 12           # reference photos fetched per shortlisted species
WORK = Path(os.environ.get("OW_SCAN_WORK", "work/scan"))

# Countries whose national list is used for the shortlist when scanning in a sub-region.
COUNTRY_OF = {"ID-BA": "ID"}


# ---------------------------------------------------------------- small helpers
_lock = threading.Lock()
_last = [0.0]


def _get(url: str, params: dict | None = None, timeout: int = 40, pace: float = 0.0) -> bytes:
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    if pace:
        with _lock:
            wait = _last[0] + pace - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            _last[0] = time.monotonic()
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
                return r.read()
        except Exception:  # noqa: BLE001
            if attempt == 3:
                raise
            time.sleep(2 + attempt * 3)
    raise RuntimeError("unreachable")


def _fetch_pack(region: str) -> Path | None:
    """Region pack from the public packs release, cached by version."""
    WORK.mkdir(parents=True, exist_ok=True)
    try:
        idx = json.loads(_get(PACKS + "index.json"))
    except Exception as e:  # noqa: BLE001
        print(f"index.json failed: {e!r}", flush=True)
        idx = []
    entry = next((p for p in (idx.get("packs", idx) if isinstance(idx, dict) else idx) if p.get("region_id") == region), None)
    if entry is None:
        return None
    dest = WORK / f"{region}.{entry['version']}.owpack"
    if not dest.exists():
        for old in WORK.glob(f"{region}.*.owpack"):
            old.unlink()
        t = time.monotonic()
        dest.write_bytes(_get(PACKS + f"{region}.owpack", timeout=600))
        print(f"pack {region} v{entry['version']} {dest.stat().st_size / 1e6:.0f} MB in {time.monotonic() - t:.0f}s", flush=True)
    return dest


def _fetch_boost(region: str) -> Path | None:
    dest = WORK / f"{region}.bioclip2.owboost"
    try:
        dest.write_bytes(_get(PACKS + f"{region}.bioclip2.owboost", timeout=600))
        return dest
    except Exception:  # noqa: BLE001
        return dest if dest.exists() else None


# ---------------------------------------------------------------- the model
class Model:
    def __init__(self):
        import open_clip
        import torch

        torch.set_num_threads(os.cpu_count() or 4)
        self.torch = torch
        self.model, _, self.preprocess = open_clip.create_model_and_transforms(HUB)
        self.model.eval()
        self.tok = open_clip.get_tokenizer(HUB)

    def images(self, ims: list, batch: int = 32) -> np.ndarray:
        out = []
        with self.torch.no_grad():
            for i in range(0, len(ims), batch):
                x = self.torch.stack([self.preprocess(im) for im in ims[i:i + batch]])
                e = self.model.encode_image(x).float()
                out.append(self.torch.nn.functional.normalize(e, dim=-1).numpy())
        return np.concatenate(out) if out else np.zeros((0, 768), np.float32)

    def texts(self, texts: list[str], batch: int = 256) -> np.ndarray:
        out = []
        with self.torch.no_grad():
            for i in range(0, len(texts), batch):
                e = self.model.encode_text(self.tok(texts[i:i + batch])).float()
                out.append(self.torch.nn.functional.normalize(e, dim=-1).numpy())
                if i and i % (batch * 20) == 0:
                    print(f"  names {i}/{len(texts)}", flush=True)
        return np.concatenate(out) if out else np.zeros((0, 768), np.float32)


def _query_views(model: Model, pil_images: list) -> np.ndarray:
    """Each user photo seen three ways (as is, mirrored, centre zoom); one vector per photo."""
    from PIL import ImageOps

    vecs = []
    for im in pil_images:
        w, h = im.size
        s = int(min(w, h) * 0.7)
        zoom = im.crop(((w - s) // 2, (h - s) // 2, (w + s) // 2, (h + s) // 2))
        v = model.images([im, ImageOps.mirror(im), zoom]).mean(0)
        vecs.append(v / (np.linalg.norm(v) + 1e-8))
    return np.stack(vecs)


# ---------------------------------------------------------------- shortlist index
class Index:
    """Every species of the country (name vectors) + the region's reference photos (image vectors)."""

    def __init__(self, model: Model, region: str):
        self.region = region
        packs = [region] + ([COUNTRY_OF[region]] if region in COUNTRY_OF else [])
        self.names: list[dict] = []
        by_name: dict[str, int] = {}
        region_ids: dict[int, int] = {}
        versions = []
        for k, pid in enumerate(packs):
            pack = _fetch_pack(pid)
            if pack is None:
                continue
            versions.append(pack.stem)
            con = sqlite3.connect(pack)
            for sid, sci, common, kingdom, family, genus in con.execute(
                    "SELECT id, scientific_name, common_name, kingdom, family, genus FROM species"):
                key = sci.lower()
                if key not in by_name:
                    by_name[key] = len(self.names)
                    self.names.append({"scientific_name": sci, "common_name": common or "", "kingdom": kingdom or "",
                                       "family": family or "", "genus": genus or "", "in_region": k == 0})
                elif k == 0:
                    self.names[by_name[key]]["in_region"] = True
                if k == 0:
                    region_ids[sid] = by_name[key]
            con.close()
        cache = WORK / f"names-{'+'.join(versions)}.npy"
        if cache.exists():
            self.text = np.load(cache)
        else:
            t = time.monotonic()
            texts = []
            for n in self.names:
                tax = " ".join(x for x in (n["kingdom"], n["family"], n["genus"]) if x)
                texts.append(f"a photo of {tax} {n['scientific_name']}" +
                             (f" with common name {n['common_name']}" if n["common_name"] else "") + ".")
            print(f"embedding {len(texts)} species names (first run only)", flush=True)
            self.text = model.texts(texts).astype(np.float16)
            for old in WORK.glob("names-*.npy"):
                old.unlink()
            np.save(cache, self.text)
            print(f"names done in {time.monotonic() - t:.0f}s", flush=True)
        self.text = self.text.astype(np.float32)
        # Region reference photos (BioCLIP 2 fingerprints from the boost file).
        self.offset = 0.0
        self.img_vecs = np.zeros((0, 768), np.float32)
        self.img_owner = np.zeros((0,), np.int64)
        boost = _fetch_boost(region)
        if boost is not None:
            con = sqlite3.connect(boost)
            meta = dict(con.execute("SELECT key, value FROM meta").fetchall())
            self.offset = float(meta.get("text_offset") or 0.0)
            vs, owners = [], []
            for sid, blob, scale, kind in con.execute("SELECT species_id, vec, scale, kind FROM prototype"):
                if kind != "image" or sid not in region_ids:
                    continue
                v = np.frombuffer(blob, dtype=np.int8).astype(np.float32) * scale
                vs.append(v / (np.linalg.norm(v) + 1e-8))
                owners.append(region_ids[sid])
            con.close()
            if vs:
                self.img_vecs, self.img_owner = np.stack(vs), np.array(owners)
        print(f"index {region}: {len(self.names)} species by name, {len(self.img_vecs)} reference photos, "
              f"text offset {self.offset:.3f}", flush=True)

    def shortlist(self, q: np.ndarray, k: int = SHORTLIST) -> list[tuple[int, float]]:
        qm = q.mean(0)
        qm /= np.linalg.norm(qm) + 1e-8
        score = self.text @ qm + self.offset
        if len(self.img_vecs):
            sims = self.img_vecs @ qm
            best = np.full(len(self.names), -1.0, np.float32)
            np.maximum.at(best, self.img_owner, sims)
            score = np.maximum(score, best)
        # Local plants first when it's close: a small nudge, never enough to override a clear match.
        score = score + np.array([0.01 if n["in_region"] else 0.0 for n in self.names], np.float32)
        order = np.argsort(-score)
        out, seen_genus = [], {}
        for i in order:
            g = self.names[i]["genus"]
            if seen_genus.get(g, 0) >= 6:      # keep room for other genera
                continue
            seen_genus[g] = seen_genus.get(g, 0) + 1
            out.append((int(i), float(score[i])))
            if len(out) >= k:
                break
        return out


# ---------------------------------------------------------------- verify against real photos
def inat_photos(scientific: str, exclude_obs: set[int] | None = None) -> list[dict]:
    """Community-confirmed iNaturalist photos: wild (research grade) and garden/potted (captive, community
    ID agrees). Only openly licensed photos."""
    out = []
    for captive, want in (("false", WILD), ("true", GARDEN)):
        params = {"taxon_name": scientific, "photos": "true", "photo_license": LICENSES, "per_page": str(want * 2),
                  "order_by": "votes", "captive": captive}
        if captive == "false":
            params["quality_grade"] = "research"
        try:
            data = json.loads(_get(INAT, params, pace=1.05))
        except Exception as e:  # noqa: BLE001
            print(f"inat {scientific} failed: {e!r}", flush=True)
            continue
        got = 0
        want_name = scientific.lower().split()[:2]
        for obs in data.get("results") or []:
            taxon = obs.get("taxon") or {}
            if (taxon.get("name") or "").lower().split()[:2] != want_name:
                continue
            if captive == "true" and obs.get("community_taxon_id") != taxon.get("id"):
                continue
            if exclude_obs and obs.get("id") in exclude_obs:
                continue
            ph = (obs.get("photos") or [None])[0]
            if not ph or not ph.get("url"):
                continue
            user = obs.get("user") or {}
            out.append({"url": ph["url"].replace("/square.", "/small."), "garden": captive == "true",
                        "credit": f"{user.get('name') or user.get('login') or 'iNaturalist user'} "
                                  f"({(ph.get('license_code') or '').upper()}), iNaturalist",
                        "obs": obs.get("id")})
            got += 1
            if got >= want:
                break
    return out


def _load(url: str):
    from PIL import Image

    try:
        return Image.open(io.BytesIO(_get(url, timeout=30))).convert("RGB")
    except Exception:  # noqa: BLE001
        return None


def _thumb(im, size: int = 240) -> str:
    im = im.copy()
    im.thumbnail((size, size))
    b = io.BytesIO()
    im.save(b, "JPEG", quality=72)
    return base64.b64encode(b.getvalue()).decode()


def identify(model: Model, index: Index, pil_images: list, exclude_obs: set[int] | None = None) -> dict:
    t0 = time.monotonic()
    q = _query_views(model, pil_images)
    short = index.shortlist(q)
    print("shortlist: " + ", ".join(f"{index.names[i]['scientific_name']} {s:.3f}" for i, s in short), flush=True)
    # Fetch reference photos for every candidate (iNaturalist API is paced to ~1 request/s).
    refs = {i: inat_photos(index.names[i]["scientific_name"], exclude_obs) for i, _ in short}
    jobs = [(i, r) for i, rs in refs.items() for r in rs]
    with ThreadPoolExecutor(16) as ex:
        imgs = list(ex.map(lambda j: _load(j[1]["url"]), jobs))
    ok = [(j, im) for j, im in zip(jobs, imgs) if im is not None]
    print(f"verifying against {len(ok)} real photos", flush=True)
    vecs = model.images([im for _, im in ok]) if ok else np.zeros((0, 768), np.float32)
    results = []
    for i, s1 in short:
        rows = [k for k, ((ci, _), _) in enumerate(ok) if ci == i]
        if not rows:
            results.append({"i": i, "score": s1 - 0.05, "refs": 0, "best": None})
            continue
        sims = q @ vecs[rows].T                          # user photos x reference photos
        top = np.sort(sims, axis=1)[:, ::-1][:, :5]      # 5 closest real photos per user photo
        score = float(top.mean())
        best_k = rows[int(np.argmax(sims.max(0)))]
        results.append({"i": i, "score": score, "refs": len(rows), "best": best_k})
    results.sort(key=lambda r: -r["score"])
    s = np.array([r["score"] for r in results])
    p = np.exp((s - s.max()) / 0.012)
    p /= p.sum()
    top, second = results[0], results[1] if len(results) > 1 else None
    margin = top["score"] - (second["score"] if second else 0)
    same_genus = second is not None and index.names[top["i"]]["genus"] == index.names[second["i"]]["genus"]
    if top["refs"] >= 5 and top["score"] >= 0.70 and margin >= 0.03:
        verdict = "confident"
    elif top["refs"] >= 3 and top["score"] >= 0.60 and margin >= 0.012:
        verdict = "likely"
    else:
        verdict = "unsure"
    cands = []
    for r, prob in list(zip(results, p))[:5]:
        n = index.names[r["i"]]
        c = {"scientific_name": n["scientific_name"], "common_name": n["common_name"], "family": n["family"],
             "score": round(r["score"], 4), "probability": round(float(prob), 3), "reference_photos": r["refs"],
             "in_region_pack": n["in_region"]}
        if r["best"] is not None:
            (_, ref), im = ok[r["best"]]
            c["example_photo_jpeg_b64"] = _thumb(im)
            c["example_credit"] = ref["credit"]
            c["example_is_garden"] = ref["garden"]
        cands.append(c)
    return {"status": "done", "verdict": verdict, "genus_only": verdict != "confident" and same_genus,
            "candidates": cands, "seconds": round(time.monotonic() - t0), "method": "bioclip2-shortlist+inat-verify-v1"}


# ---------------------------------------------------------------- inbox driver
def process_inbox(inbox: Path) -> int:
    """Handle every scans/<id>/ that has meta.json but no result.json. Returns how many were processed."""
    from PIL import Image

    pending = sorted(p.parent for p in (inbox / "scans").glob("*/meta.json") if not (p.parent / "result.json").exists())
    if not pending:
        print("no pending scans", flush=True)
        return 0
    model = Model()
    indexes: dict[str, Index] = {}
    for d in pending:
        try:
            meta = json.loads((d / "meta.json").read_text())
            region = meta.get("region") or "ID-BA"
            if region not in indexes:
                indexes[region] = Index(model, region)
            ims = [Image.open(p).convert("RGB") for p in sorted(d.glob("*.jpg"))][:6]
            if not ims:
                raise ValueError("no photos")
            res = identify(model, indexes[region], ims)
        except Exception as e:  # noqa: BLE001
            print(f"scan {d.name} failed: {e!r}", flush=True)
            res = {"status": "failed", "error": "The online check could not finish. Please try again later."}
        res["id"] = d.name
        res["finished"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        (d / "result.json").write_text(json.dumps(res))
        print(f"scan {d.name}: {res.get('verdict')} "
              f"{[c['scientific_name'] for c in res.get('candidates', [])][:3]} in {res.get('seconds')}s", flush=True)
        top = (res.get("candidates") or [{}])[0].get("scientific_name")
        if meta.get("share") and res.get("verdict") == "confident" and top:
            # The user agreed to share: a confident answer files the photos as a report for that plant,
            # which the weekly bot adds to the library (that's how the offline app learns).
            import re
            import shutil

            dest = inbox / "reports" / region / re.sub(r"[^a-z0-9]+", "_", top.lower()).strip("_") / f"scan{d.name}"
            dest.mkdir(parents=True, exist_ok=True)
            photos = []
            for p in sorted(d.glob("*.jpg")):
                shutil.move(str(p), dest / p.name)
                photos.append({"file": p.name, "part": p.stem.split("_", 1)[-1]})
            (dest / "report.json").write_text(json.dumps({
                "kind": "online_check", "predicted": meta.get("offline_top"), "chosen": top, "region": region,
                "online_score": res["candidates"][0]["score"], "photos": photos, "license": "CC BY 4.0",
                "own_photos": True, "app_version": meta.get("app_version")}, indent=2))
        for p in d.glob("*.jpg"):
            p.unlink()              # private by default: the photos leave the inbox once checked
    return len(pending)


def expire(inbox: Path, days: float = 2.0) -> int:
    """Results are only needed until the phone has read them: drop scans older than `days`."""
    import shutil

    n = 0
    cutoff = time.time() - days * 86400
    import calendar

    for d in (inbox / "scans").glob("*"):
        r = d / "result.json"
        if not r.exists():
            continue
        try:
            done = calendar.timegm(time.strptime(json.loads(r.read_text())["finished"], "%Y-%m-%dT%H:%M:%SZ"))
        except Exception:  # noqa: BLE001
            done = 0
        if done < cutoff:   # checkout resets file times, so go by the time written inside the result
            shutil.rmtree(d)
            n += 1
    return n


# ---------------------------------------------------------------- accuracy test
def evaluate(region: str, species: list[str], per_species: int = 1) -> dict:
    """Leave-one-out: take a real observation photo of each species (wild and garden), hide that observation
    from the reference photos, and see what the online check answers."""
    model = Model()
    index = Index(model, region)
    hit1 = hit5 = n = 0
    rows = []
    for sci in species:
        for captive in ("false", "true"):
            params = {"taxon_name": sci, "photos": "true", "per_page": "10", "order_by": "random",
                      "captive": captive}
            if captive == "false":
                params["quality_grade"] = "research"
            try:
                obs = [o for o in json.loads(_get(INAT, params, pace=1.05)).get("results") or []
                       if (o.get("taxon") or {}).get("name", "").lower().split()[:2] == sci.lower().split()[:2]
                       and o.get("photos")][:per_species]
            except Exception:  # noqa: BLE001
                continue
            for o in obs:
                ims = [im for im in (_load(p["url"].replace("/square.", "/medium.")) for p in o["photos"][:3]) if im]
                if not ims:
                    continue
                res = identify(model, index, ims, exclude_obs={o["id"]})
                names = [c["scientific_name"].lower() for c in res["candidates"]]
                ok1, ok5 = names[:1] == [sci.lower()], sci.lower() in names
                n += 1
                hit1 += ok1
                hit5 += ok5
                rows.append({"species": sci, "garden": captive == "true", "top1": ok1, "top5": ok5,
                             "verdict": res["verdict"], "answer": names[0] if names else None,
                             "score": res["candidates"][0]["score"] if res["candidates"] else None,
                             "seconds": res["seconds"]})
                print("EVAL " + json.dumps(rows[-1]), flush=True)
    summary = {"n": n, "top1": round(hit1 / n, 3) if n else None, "top5": round(hit5 / n, 3) if n else None}
    by_v = {}
    for r in rows:
        v = by_v.setdefault(r["verdict"], [0, 0])
        v[0] += 1
        v[1] += r["top1"]
    summary["right_when"] = {k: f"{b}/{a}" for k, (a, b) in by_v.items()}
    print("EVAL-SUMMARY " + json.dumps(summary), flush=True)
    return summary
