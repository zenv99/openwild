import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { handle } from '../src/handler.js'
import { memoryDb, memoryR2 } from './db.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const schema = fs.readFileSync(path.join(root, 'schema.sql'), 'utf8')
const INSTALL = '11111111-1111-4111-8111-111111111111'
const OTHER = '22222222-2222-4222-8222-222222222222'

function env(extra = {}) {
  let clock = Date.parse('2026-01-15T00:00:00Z')
  return {
    DB: memoryDb(schema),
    PHOTOS: memoryR2(),
    ADMIN_TOKEN: 'test-admin-token-please',
    now: () => clock,
    ...extra,
  }
}

async function call(e, method, pathname, { body, install, token } = {}) {
  const headers = { 'content-type': 'application/json' }
  if (install) headers['x-install-id'] = install
  if (token) headers.authorization = `Bearer ${token}`
  const res = await handle(new Request(`https://profiles.test${pathname}`, {
    method,
    headers,
    body: body == null ? undefined : JSON.stringify(body),
  }), e)
  const text = await res.text()
  const json = (res.headers.get('content-type') || '').includes('json') && text ? JSON.parse(text) : null
  return { status: res.status, json, text }
}

const tiny = btoa('not-a-real-jpeg-but-long-enough')

test('there is no account table and no mail route', async () => {
  const tables = schema.slice(schema.indexOf('CREATE TABLE'))
  assert.equal(/email/i.test(tables), false)
  assert.equal(/password/i.test(tables), false)
  const e = env()
  const health = await call(e, 'GET', '/v1/health')
  assert.equal(health.json.accounts, false)
  const mail = await call(e, 'POST', '/v1/auth/start', { body: { email: 'ada@mit.edu' } })
  assert.equal(mail.status, 404)
})

test('a blank name is Anonymous and the picture is dropped', async () => {
  const e = env()
  const created = await call(e, 'POST', '/v1/submissions', {
    install: INSTALL,
    body: {
      kind: 'review', species: 'Quercus robur', regionId: 'GB', body: 'A large oak.', rating: 5,
      shareLocation: true, lat: 51.26, lon: -0.24, avatarBase64: tiny,
    },
  })
  assert.equal(created.status, 201)
  assert.equal(created.json.submission.credit, 'Anonymous')
  assert.equal(created.json.submission.avatarUrl, null)
  assert.equal(created.json.submission.title, '')
  assert.equal(created.json.submission.location, null)
  assert.equal(created.json.trustWeight, 1)
  assert.equal(JSON.stringify(created.json).includes(INSTALL), false)
  const listed = await call(e, 'GET', '/v1/submissions?species=Quercus%20robur')
  assert.equal(listed.json.submissions[0].credit, 'Anonymous')
  assert.equal(listed.json.submissions[0].avatarUrl, null)
})

test('a named send keeps title and affiliation, and the picture stays hidden until approved', async () => {
  const e = env()
  const created = await call(e, 'POST', '/v1/submissions', {
    install: INSTALL,
    body: {
      kind: 'review', species: 'Hedera helix', regionId: 'GB', body: 'Ivy on the wall.',
      credit: 'Ada Lovelace', title: 'Student', affiliation: 'Example University',
      avatarBase64: tiny, shareLocation: true, lat: 51.26, lon: -0.24,
    },
  })
  assert.equal(created.json.status, 'public')
  assert.equal(created.json.avatarStatus, 'pending')
  assert.equal(created.json.submission.credit, 'Ada Lovelace')
  assert.equal(created.json.submission.title, 'Student')
  assert.equal(created.json.submission.avatarUrl, null)
  assert.equal(created.json.submission.location.lat, 51.3)
  assert.equal(created.json.submission.location.lon, -0.2)
  const hidden = await call(e, 'GET', `/v1/media/avatars/${created.json.id}`)
  assert.equal(hidden.status, 404)
  const approved = await call(e, 'POST', `/v1/admin/submissions/${created.json.id}`, {
    token: e.ADMIN_TOKEN, body: { action: 'approve' },
  })
  assert.equal(approved.json.avatarStatus, 'public')
  assert.equal(approved.json.trustWeight, 1)
  const shown = await call(e, 'GET', '/v1/submissions?species=Hedera%20helix')
  assert.equal(shown.json.submissions[0].avatarUrl, `/v1/media/avatars/${created.json.id}`)
  const media = await call(e, 'GET', `/v1/media/avatars/${created.json.id}`)
  assert.equal(media.status, 200)
})

test('photos stay hidden until an admin approves them', async () => {
  const e = env()
  const photo = await call(e, 'POST', '/v1/submissions', {
    install: INSTALL,
    body: { kind: 'photo', species: 'Fagus sylvatica', regionId: 'GB', credit: 'Ada', license: 'cc-by', photoBase64: tiny },
  })
  assert.equal(photo.json.status, 'pending')
  const pub = await call(e, 'GET', '/v1/submissions?species=Fagus%20sylvatica')
  assert.equal(pub.json.submissions.length, 0)
  const blocked = await call(e, 'GET', '/v1/admin/queue')
  assert.equal(blocked.status, 401)
  const queue = await call(e, 'GET', '/v1/admin/queue', { token: e.ADMIN_TOKEN })
  assert.equal(queue.json.queue[0].status, 'pending')
  assert.equal(JSON.stringify(queue.json).includes(INSTALL), false)
  await call(e, 'POST', `/v1/admin/submissions/${photo.json.id}`, { token: e.ADMIN_TOKEN, body: { action: 'approve' } })
  const visible = await call(e, 'GET', '/v1/submissions?species=Fagus%20sylvatica')
  assert.equal(visible.json.submissions[0].licenseLabel, 'CC BY 4.0')
  assert.equal(visible.json.submissions[0].credit, 'Ada')
})

test('three reports hide an item', async () => {
  const e = env()
  const review = await call(e, 'POST', '/v1/submissions', {
    install: INSTALL,
    body: { kind: 'id_feedback', species: 'Taxus baccata', regionId: 'GB', body: 'A note.', credit: 'Ada' },
  })
  for (let i = 0; i < 2; i++) {
    const r = await call(e, 'POST', `/v1/submissions/${review.json.id}/report`, { body: { reason: 'wrong plant' } })
    assert.equal(r.json.status, 'public')
  }
  const third = await call(e, 'POST', `/v1/submissions/${review.json.id}/report`, { body: { reason: 'still wrong' } })
  assert.equal(third.json.status, 'hidden')
  const pub = await call(e, 'GET', '/v1/submissions?species=Taxus%20baccata')
  assert.equal(pub.json.submissions.length, 0)
})

test('the install id can delete its own send and nobody else can', async () => {
  const e = env()
  const created = await call(e, 'POST', '/v1/submissions', {
    install: INSTALL,
    body: { kind: 'review', species: 'Ilex aquifolium', regionId: 'GB', body: 'Holly.', credit: 'Ada' },
  })
  const denied = await call(e, 'DELETE', `/v1/submissions/${created.json.id}`, { install: OTHER })
  assert.equal(denied.status, 403)
  const gone = await call(e, 'DELETE', `/v1/submissions/${created.json.id}`, { install: INSTALL })
  assert.equal(gone.status, 204)
  const pub = await call(e, 'GET', '/v1/submissions?species=Ilex%20aquifolium')
  assert.equal(pub.json.submissions.length, 0)
})

test('rate limit and a rejected license', async () => {
  const e = env()
  for (let i = 0; i < 30; i++) {
    const res = await call(e, 'POST', '/v1/submissions', {
      install: INSTALL,
      body: { kind: 'review', species: 'Plant ' + i, regionId: 'GB', body: 'n' },
    })
    assert.equal(res.status, 201)
  }
  const blocked = await call(e, 'POST', '/v1/submissions', {
    install: INSTALL,
    body: { kind: 'review', species: 'One more', regionId: 'GB', body: 'n' },
  })
  assert.equal(blocked.status, 429)
  const bad = await call(e, 'POST', '/v1/submissions', {
    install: OTHER,
    body: { kind: 'photo', species: 'Fagus sylvatica', regionId: 'GB', license: 'cc-by-nc' },
  })
  assert.equal(bad.status, 400)
})
