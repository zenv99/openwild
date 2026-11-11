# Confidence Scoring

Reference implementation: `pipeline/openwild_pipeline/confidence.py`. App implementation: `android/.../match/Confidence.kt`. Both must produce the same results; `pipeline/tests/test_confidence.py` and `android/app/src/test/.../ConfidenceTest.kt` share the same fixture.

## Inputs
- `n` photo fingerprints `e_p` (L2-normalised, length D).
- For each species `s`: prototypes `v_{s,k}` (dequantised) and a prior `π_s`.
- Parameters from the pack `meta` table: `T` (temperature), `α` (photo_alpha), `β` (prior_beta), thresholds.

## Steps
1. **Similarity.** `sim(p,s) = max_k (e_p · v_{s,k})`, minus `text_proto_penalty` when the best prototype is a text prototype.
2. **Per-photo distribution.** `ℓ_p(s) = log_softmax_s( sim(p,s) / T )`.
3. **Prior.** `π_s ∝ (obs_count_s + 1)^0.5 × (month_s[m] / 255 + 0.05)` for the current month `m`, normalised over species.
4. **Fusion.** `L(s) = α · Σ_p ℓ_p(s) + β · log π_s`, then `P(s) = softmax_s(L(s))`.
   `α < 1` accounts for photos of the same specimen not being independent evidence.
5. **Open-set check.** `μ = mean_p sim(p, s*)` for the top species `s*`. If `μ < open_set_threshold` → **Not in library**.
6. **Agreement.** Fraction of photos whose own top species equals `s*`.
7. **Tier.**
   - **High**: `P(s*) ≥ high_threshold` and margin `P(s*) − P(s₂) ≥ 0.30` and (n = 1 or agreement ≥ 0.5)
   - **Medium**: `P(s*) ≥ medium_threshold`
   - **Low**: otherwise

The displayed confidence score is `round(100 × P(s*))`%.

## Calibration
`pipeline calibrate` fits `T` and `α` on held-out photos (minimising negative log-likelihood) and sets `open_set_threshold` from the similarity distribution of out-of-region species, targeting ≤ 5% false "known" matches. Results are written into the pack's `meta` table.
