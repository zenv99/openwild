// School-domain check. Domain snapshots: server/data/SOURCES.md.
// Parent walk matches JetBrains swot: listing mit.edu covers cs.mit.edu.
// Keep the free-mail denylist in sync with AcademicDomains.kt.

const FREE_MAIL = new Set([
  'gmail.com', 'googlemail.com', 'yahoo.com', 'ymail.com', 'outlook.com', 'hotmail.com',
  'live.com', 'msn.com', 'icloud.com', 'me.com', 'mac.com', 'proton.me', 'protonmail.com',
  'pm.me', 'aol.com', 'gmx.com', 'gmx.net', 'yandex.com', 'yandex.ru', 'qq.com', '163.com',
  'mail.com', 'zoho.com', 'fastmail.com', 'tutanota.com', 'tuta.com',
])

export function parseDomainList(text) {
  const set = new Set()
  for (const line of String(text || '').split(/\r?\n/)) {
    const v = line.trim().toLowerCase()
    if (!v || v.startsWith('#')) continue
    set.add(v)
  }
  return set
}

/** Full domain, then parents, stopping before the bare TLD. */
export function parents(domain) {
  const parts = domain.split('.')
  const out = []
  for (let i = 0; i < parts.length - 1; i++) out.push(parts.slice(i).join('.'))
  return out
}

export function suffixAcademic(domain) {
  const p = domain.split('.')
  if (p.length >= 2 && p[p.length - 1] === 'edu') return true
  if (p.length >= 3 && p[p.length - 1].length === 2 && (p[p.length - 2] === 'ac' || p[p.length - 2] === 'edu')) return true
  return false
}

export function domainOf(email) {
  const t = String(email || '').trim().toLowerCase()
  const at = t.lastIndexOf('@')
  if (at <= 0 || at === t.length - 1) return null
  const local = t.slice(0, at)
  const domain = t.slice(at + 1).replace(/\.+$/, '')
  if (!local || domain.includes('@') || /\s/.test(domain) || !domain.includes('.')) return null
  if (!/^[a-z0-9.-]+$/.test(domain) || domain.includes('..') || domain.startsWith('.') || domain.startsWith('-')) return null
  return domain
}

export function checkEmail(email, domains, abused) {
  const domain = domainOf(email)
  if (!domain) return { academic: false, matchedDomain: null, reason: 'not-an-email' }
  const chain = parents(domain)
  if (chain.some((d) => FREE_MAIL.has(d))) return { academic: false, matchedDomain: null, reason: 'free-mail' }
  if (chain.some((d) => abused.has(d))) return { academic: false, matchedDomain: null, reason: 'abused' }
  const hit = chain.find((d) => domains.has(d))
  if (hit) return { academic: true, matchedDomain: hit, reason: 'listed' }
  if (suffixAcademic(domain)) return { academic: true, matchedDomain: domain, reason: 'suffix' }
  return { academic: false, matchedDomain: null, reason: 'not-academic' }
}

export function createMatcher(domainText, abusedText) {
  const domains = parseDomainList(domainText)
  const abused = parseDomainList(abusedText)
  return {
    check(email) {
      return checkEmail(email, domains, abused)
    },
  }
}
