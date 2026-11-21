// Same order as android/.../core/Profiles.kt TrustWeight and server/test/fixtures/trust-weights.json.

export function trustWeight({ verifiedAcademic, visibility, accountAgeDays, kind, approved }) {
  if (kind === 'photo' && !approved) return 0
  if (visibility === 'anonymous') return 1
  if (verifiedAcademic) return 2
  if (Number(accountAgeDays) >= 7) return 1.25
  return 0.75
}
