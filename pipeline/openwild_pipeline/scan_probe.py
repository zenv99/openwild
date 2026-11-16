import urllib.request
from .commercial_photos import USER_AGENT
U = "https://medialib.naturalis.nl/file/id/L.2693127/format/"
for fmt in ("large", "medium", "small", "master"):
    for ua in (USER_AGENT, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"):
        try:
            req = urllib.request.Request(U + fmt, headers={"User-Agent": ua, "Accept": "image/*"})
            with urllib.request.urlopen(req, timeout=60) as r:
                d = r.read(90_000_000)
            print("SCAN", fmt, ua[:20], "OK", len(d), r.headers.get("Content-Type"))
        except Exception as e:
            print("SCAN", fmt, ua[:20], "FAIL", repr(e)[:80])
for url in ("https://d2jcv3kl45hlgi.cloudfront.net/c55830bb1a3623548ba4f418cd07e3f0.jpg",):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=60) as r:
            print("SCAN kew OK", len(r.read(90_000_000)))
    except Exception as e:
        print("SCAN kew FAIL", repr(e)[:80])
