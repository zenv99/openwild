# Academic domain snapshots

These files are data, not OpenWild code. Each keeps its own license.

| File | Source | Snapshot | License |
|---|---|---|---|
| `academic-domains.txt` | [Hipo/university-domains-list](https://github.com/Hipo/university-domains-list) `world_universities_and_domains.json` | commit `a8fb652f5032801de7125a814aedf213b262a1a4` (2026-11-02) | MIT, see `LICENSE-university-domains-list.txt` |
| `academic-domains-abused.txt` | [JetBrains/swot](https://github.com/JetBrains/swot) `lib/domains/abused.txt` | commit `40feb2381b796abb1cc1e15253e8c72ecdbbbee0` (2026-11-20) | MIT, see `LICENSE-swot.txt` (copyright Lee Reilly, 2013; the repository is maintained by JetBrains) |

Only the domain strings are kept from the Hipo JSON (names, countries, and web pages are not copied). swot's abused list is copied as domain strings. The parent-domain match (a school listed as `mit.edu` also covers `student.cs.mit.edu`) is the rule swot documents. OpenWild did not copy swot's domain tree or Hipo's server code.

Refresh: download the two upstream files, rebuild the text lists, then run `node server/scripts/embed-domains.mjs` so `server/src/domainData.js` matches.
