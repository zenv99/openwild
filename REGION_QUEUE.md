# Region queue

The weekly data bot fills one region per week, top to bottom. For each region it:
1. runs the **Region data** GitHub Action (every GBIF plant & fungus species + names + Wikipedia one-liners),
2. writes bulk one-liners (edible / poisonous / benefit) in `content/quick/<region>_*.csv` for as many of that region's species as it can verify, most-common species first,
3. ticks the box below and updates `COVERAGE.md`.

Then it moves to the next region. Already-filled regions get topped up once the queue is done.

- [ ] ID-BA — Bali, Indonesia (in progress: species list + 275 one-liners)
- [ ] ID — Indonesia (rest)
- [ ] PH — Philippines
- [ ] TH — Thailand
- [ ] MY — Malaysia
- [ ] VN — Vietnam
- [ ] SG — Singapore
- [ ] KH — Cambodia
- [ ] LA — Laos
- [ ] MM — Myanmar
- [ ] TL — Timor-Leste
- [ ] PG — Papua New Guinea
- [ ] AU — Australia
- [ ] NZ — New Zealand
- [ ] US-HI — Hawaii
- [ ] IN — India
- [ ] LK — Sri Lanka
- [ ] JP — Japan
- [ ] TW — Taiwan
- [ ] KR — South Korea
- [ ] CN — China
- [ ] US-CA — California
- [ ] US — United States
- [ ] CA — Canada
- [ ] MX — Mexico
- [ ] CR — Costa Rica
- [ ] CO — Colombia
- [ ] BR — Brazil
- [ ] PE — Peru
- [ ] GB — United Kingdom
- [ ] DE — Germany
- [ ] FR — France
- [ ] ES — Spain
- [ ] IT — Italy
- [ ] ZA — South Africa
- [ ] KE — Kenya
- [ ] (all remaining regions in regions/regions.yaml)
