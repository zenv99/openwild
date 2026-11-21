import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { checkEmail, createMatcher, parseDomainList } from '../src/academic.js'
import { ABUSED_TEXT, DOMAIN_TEXT } from '../src/domainData.js'
import { handle } from '../src/handler.js'
import { trustWeight } from '../src/trust.js'
import { memoryDb, memoryR2 } from './db.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const schema = fs.readFileSync(path.join(root, 'schema.sql'), 'utf8')
const domainFile = fs.readFileSync(path.join(root, 'data/academic-domains.txt'), 'utf8')
const abusedFile = fs.readFileSync(path.join(root, 'data/academic-domains-abused.txt'), 'utf8')
const weights = JSON.parse(fs.readFileSync(path.join(root, 'test/fixtures/trust-weights.json'), 'utf8'))

function env(extra = {}) {
  const outbox = []
  let clock = Date.parse('2026-01-15T00:00:00Z')
  return {
    DB: memoryDb(schema),
    PHOTOS: memoryR2(),
    DOMAIN_TEXT,
    ABUSED_TEXT,
    ADMIN_TOKEN: 'test-admin-token-please',
    outbox,
    now: () => clock,
    setNow(ms) { clock = ms },
    MAILER: async ({ email, code }) => { outbox.push({ email, code }) },
    ...extra,
  }
}

async function call(e, method, pathname, { body, token } = {}) {
  const headers = { 'content-type': 'application/json' }
  if (token) headers.authorization = `Bearer ${token}`
  const res = await handle(new Request(`https://profiles.test${pathname}`, {
    method,
    headers,
    body: body == null ? undefined : JSON.stringify(body),
  }), e)
  const text = await res.text()
  let json = null
  if (text) json = JSON.parse(text)
  return { status: res.status, json, text }
}

async function signIn(e, email) {
  const start = await call(e, 'POST', '/v1/auth/start', { body: { email } })
  assert.equal(start.status, 202)
  const code = e.outbox.at(-1).code
  assert.equal(e.outbox.at(-1).email, email)
  const done = await call(e, 'POST', '/v1/auth/verify', { body: { email, code } })
  assert.equal(done.status, 200, JSON.stringify(done.json))
  return done.json
}

test('embedded domain list matches the licensed snapshot', () => {
  assert.equal(DOMAIN_TEXT, domainFile)
  assert.equal(ABUSED_TEXT, abusedFile)
  const domains = parseDomainList(domainFile)
  assert.ok(domains.has('mit.edu'))
  assert.ok(domains.has('ox.ac.uk'))
  assert.ok(domains.has('ui.ac.id'))
  assert.ok(parseDomainList(abusedFile).has('si.edu'))
})

test('academic check: suffixes, list, free mail, abused, parent domain', () => {
  const domains = parseDomainList('mit.edu\nuni-heidelberg.de\n')
  const abused = parseDomainList('si.edu\n')
  assert.equal(checkEmail('ada@mit.edu', domains, abused).reason, 'listed')
  assert.equal(checkEmail('a@cs.mit.edu', domains, abused).matchedDomain, 'mit.edu')
  assert.equal(checkEmail('a@notaschool.edu', domains, abused).reason, 'suffix')
  assert.equal(checkEmail('a@ox.ac.uk', domains, abused).reason, 'suffix')
  assert.equal(checkEmail('a@ui.ac.id', domains, abused).reason, 'suffix')
  assert.equal(checkEmail('a@botany.uni-heidelberg.de', domains, abused).matchedDomain, 'uni-heidelberg.de')
  assert.equal(checkEmail('a@gmail.com', domains, abused).reason, 'free-mail')
  assert.equal(checkEmail('a@si.edu', domains, abused).academic, false)
  assert.equal(checkEmail('a@dept.si.edu', domains, abused).reason, 'abused')
  assert.equal(checkEmail('a@example.ac', domains, abused).academic, false)
  assert.equal(checkEmail('not-an-email', domains, abused).reason, 'not-an-email')
  const full = createMatcher(domainFile, abusedFile)
  assert.equal(full.check('student@ox.ac.uk').reason, 'listed')
  // ui.ac.id is on the swot abused snapshot, so the .ac.id suffix is not enough.
  assert.equal(full.check('student@ui.ac.id').academic, false)
  assert.equal(full.check('student@itb.ac.id').academic, true)
  assert.equal(full.check('a@si.edu').academic, false)
})

test('trust weights match the shared fixture', () => {
  for (const row of weights) {
    assert.equal(trustWeight(row), row.weight, row.name)
  }
})

test('health and missing mailer do not create an account or send mail', async () => {
  const e = env({ MAILER: undefined, RESEND_API_KEY: '', EMAIL_FROM: '' })
  const health = await call(e, 'GET', '/v1/health')
  assert.equal(health.json.emailConfigured, false)
  const start = await call(e, 'POST', '/v1/auth/start', { body: { email: 'ada@mit.edu' } })
  assert.equal(start.status, 503)
  assert.equal(start.json.error, 'email_not_configured')
  const accounts = await e.DB.prepare('SELECT COUNT(*) AS n FROM account').bind().first()
  assert.equal(Number(accounts.n), 0)
  const otps = await e.DB.prepare('SELECT COUNT(*) AS n FROM otp').bind().first()
  assert.equal(Number(otps.n), 0)
})

test('school mailbox earns a badge; a free mailbox does not; anonymous hides it', async () => {
  const e = env()
  const ada = await signIn(e, 'ada@mit.edu')
  assert.equal(ada.profile.verifiedAcademic, true)
  assert.equal(ada.profile.verifiedDomain, 'mit.edu')
  assert.equal(ada.profile.email, 'ada@mit.edu')
  const gmail = await signIn(e, 'ada@gmail.com')
  assert.equal(gmail.profile.verifiedAcademic, false)
  assert.equal(gmail.profile.verifiedDomain, null)

  const patched = await call(e, 'PATCH', '/v1/me', {
    token: ada.token,
    body: { displayName: 'Ada Lovelace', title: 'Student', affiliation: 'Example University', license: 'cc-by' },
  })
  assert.equal(patched.json.profile.displayName, 'Ada Lovelace')

  const anon = await call(e, 'PATCH', '/v1/me', { token: ada.token, body: { visibility: 'anonymous' } })
  assert.equal(anon.json.profile.email, 'ada@mit.edu')
  assert.equal(anon.json.profile.verifiedDomain, null)
  const hidden = await call(e, 'GET', `/v1/profiles/${ada.profile.id}`)
  assert.equal(hidden.status, 404)

  await call(e, 'PATCH', '/v1/me', { token: ada.token, body: { visibility: 'public' } })
  const shown = await call(e, 'GET', `/v1/profiles/${ada.profile.id}`)
  assert.equal(shown.status, 200)
  assert.equal(shown.json.verifiedAcademic, true)
  assert.equal(shown.json.verifiedDomain, 'mit.edu')
  assert.equal(JSON.stringify(shown.json).includes('ada@mit.edu'), false)
})

test('anonymous contributions never include the email, and location is opt-in', async () => {
  const e = env()
  const ada = await signIn(e, 'ada@mit.edu')
  await call(e, 'PATCH', '/v1/me', {
    token: ada.token,
    body: { displayName: 'Ada Lovelace', visibility: 'public' },
  })
  const created = await call(e, 'POST', '/v1/contributions', {
    token: ada.token,
    body: {
      kind: 'review', species: 'Quercus robur', regionId: 'GB', body: 'Large oak by the lane.',
      rating: 5, visibility: 'anonymous', shareLocation: true, coarseLat: 51.51, coarseLon: -0.12,
    },
  })
  assert.equal(created.status, 201)
  assert.equal(created.json.contribution.credit, 'Anonymous')
  assert.equal(created.json.contribution.profile, null)
  assert.equal(created.json.contribution.trustWeight, 1)
  const listed = await call(e, 'GET', '/v1/contributions?species=Quercus%20robur')
  const blob = JSON.stringify(listed.json)
  assert.equal(blob.includes('ada@mit.edu'), false)
  assert.equal(blob.includes('mit.edu'), false)
  assert.equal(blob.includes('Ada Lovelace'), false)
  assert.equal(listed.json.contributions[0].location, null)

  const named = await call(e, 'POST', '/v1/contributions', {
    token: ada.token,
    body: {
      kind: 'id_feedback', species: 'Quercus robur', regionId: 'GB', body: 'Leaves match.',
      visibility: 'public', shareLocation: true, lat: 51.26, lon: -0.24,
    },
  })
  assert.equal(named.json.contribution.credit, 'Ada Lovelace')
  assert.equal(named.json.contribution.profile.verifiedDomain, 'mit.edu')
  assert.equal(named.json.contribution.location.lat, 51.3)
  assert.equal(named.json.contribution.location.lon, -0.2)
  assert.equal(named.json.trustWeight, 2)
  assert.equal(JSON.stringify(named.json).includes('ada@mit.edu'), false)
})

test('new account photos stay hidden until an admin approves them', async () => {
  const e = env()
  const ada = await signIn(e, 'student@ox.ac.uk')
  const photo = await call(e, 'POST', '/v1/contributions', {
    token: ada.token,
    body: {
      kind: 'photo', species: 'Hedera helix', regionId: 'GB', visibility: 'public',
      license: 'cc-by', photoBase64: btoa('not-a-real-jpeg-but-long-enough'),
    },
  })
  assert.equal(photo.json.status, 'pending')
  assert.equal(photo.json.trustWeight, 0)
  const pub = await call(e, 'GET', '/v1/contributions?species=Hedera%20helix')
  assert.equal(pub.json.contributions.length, 0)
  const noAdmin = await call(e, 'GET', '/v1/admin/queue', { token: 'nope' })
  assert.equal(noAdmin.status, 401)
  const unconfigured = env()
  unconfigured.ADMIN_TOKEN = ''
  const blocked = await call(unconfigured, 'GET', '/v1/admin/queue', { token: 'test-admin-token-please' })
  assert.equal(blocked.status, 503)
  const queue = await call(e, 'GET', '/v1/admin/queue', { token: e.ADMIN_TOKEN })
  assert.equal(queue.json.queue.length, 1)
  assert.equal(JSON.stringify(queue.json).includes('ox.ac.uk'), false)
  const approved = await call(e, 'POST', `/v1/admin/contributions/${photo.json.id}`, {
    token: e.ADMIN_TOKEN,
    body: { action: 'approve' },
  })
  assert.equal(approved.json.status, 'public')
  assert.equal(approved.json.trustWeight, 2)
  const visible = await call(e, 'GET', '/v1/contributions?species=Hedera%20helix')
  assert.equal(visible.json.contributions[0].licenseLabel, 'CC BY 4.0')
  assert.equal(visible.json.contributions[0].credit, 'Anonymous')
})

test('an established account with an approved photo publishes the next one', async () => {
  const e = env()
  const ada = await signIn(e, 'ada@mit.edu')
  await call(e, 'PATCH', '/v1/me', { token: ada.token, body: { displayName: 'Ada' } })
  e.setNow(e.now() + 30 * 24 * 60 * 60 * 1000)
  const first = await call(e, 'POST', '/v1/contributions', {
    token: ada.token,
    body: { kind: 'photo', species: 'Fagus sylvatica', regionId: 'GB', visibility: 'public' },
  })
  assert.equal(first.json.status, 'pending')
  await call(e, 'POST', `/v1/admin/contributions/${first.json.id}`, {
    token: e.ADMIN_TOKEN, body: { action: 'approve' },
  })
  const second = await call(e, 'POST', '/v1/contributions', {
    token: ada.token,
    body: { kind: 'photo', species: 'Fagus sylvatica', regionId: 'GB', visibility: 'public', license: 'cc0' },
  })
  assert.equal(second.json.status, 'public')
  assert.equal(second.json.contribution.licenseLabel, 'CC0')
  const bad = await call(e, 'POST', '/v1/contributions', {
    token: ada.token,
    body: { kind: 'photo', species: 'Fagus sylvatica', regionId: 'GB', license: 'cc-by-nc' },
  })
  assert.equal(bad.status, 400)
})

test('three reports hide a public row', async () => {
  const e = env()
  const ada = await signIn(e, 'ada@mit.edu')
  const review = await call(e, 'POST', '/v1/contributions', {
    token: ada.token,
    body: { kind: 'review', species: 'Taxus baccata', regionId: 'GB', body: 'A note.', rating: 3, visibility: 'public' },
  })
  for (let i = 0; i < 2; i++) {
    const r = await call(e, 'POST', `/v1/contributions/${review.json.id}/report`, { body: { reason: 'wrong plant' } })
    assert.equal(r.json.status, 'public')
  }
  const third = await call(e, 'POST', `/v1/contributions/${review.json.id}/report`, { body: { reason: 'still wrong' } })
  assert.equal(third.json.status, 'hidden')
  const pub = await call(e, 'GET', '/v1/contributions?species=Taxus%20baccata')
  assert.equal(pub.json.contributions.length, 0)
})

test('delete removes the email and anonymizes a public contribution', async () => {
  const e = env()
  const ada = await signIn(e, 'ada@mit.edu')
  await call(e, 'PATCH', '/v1/me', { token: ada.token, body: { displayName: 'Ada Lovelace' } })
  await call(e, 'POST', '/v1/contributions', {
    token: ada.token,
    body: { kind: 'review', species: 'Ilex aquifolium', regionId: 'GB', body: 'Holly.', rating: 4, visibility: 'public' },
  })
  const exported = await call(e, 'GET', '/v1/me/export', { token: ada.token })
  assert.equal(exported.json.profile.email, 'ada@mit.edu')
  const gone = await call(e, 'DELETE', '/v1/me', { token: ada.token })
  assert.equal(gone.status, 204)
  const again = await call(e, 'GET', '/v1/me/export', { token: ada.token })
  assert.equal(again.status, 401)
  const pub = await call(e, 'GET', '/v1/contributions?species=Ilex%20aquifolium')
  assert.equal(pub.json.contributions[0].credit, 'Anonymous')
  assert.equal(JSON.stringify(pub.json).includes('ada@mit.edu'), false)
  assert.equal(JSON.stringify(pub.json).includes('Ada Lovelace'), false)
  const accounts = await e.DB.prepare('SELECT COUNT(*) AS n FROM account').bind().first()
  assert.equal(Number(accounts.n), 0)
})

test('rate limit and a wrong code', async () => {
  const e = env()
  for (let i = 0; i < 5; i++) {
    const res = await call(e, 'POST', '/v1/auth/start', { body: { email: 'ada@mit.edu' } })
    assert.equal(res.status, 202)
  }
  const blocked = await call(e, 'POST', '/v1/auth/start', { body: { email: 'ada@mit.edu' } })
  assert.equal(blocked.status, 429)
  const bad = await call(e, 'POST', '/v1/auth/verify', { body: { email: 'ada@mit.edu', code: '00000000' } })
  assert.equal(bad.status, 400)
})

test('account table has no password column', () => {
  const account = schema.slice(schema.indexOf('CREATE TABLE IF NOT EXISTS account'), schema.indexOf('CREATE TABLE IF NOT EXISTS session'))
  assert.equal(/password/i.test(account), false)
  assert.match(account, /email TEXT NOT NULL UNIQUE/)
})
