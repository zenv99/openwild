import { createMatcher } from './academic.js'
import { deliverCode } from './mailer.js'
import { normalizeLicense, LICENSES } from './licenses.js'
import { trustWeight } from './trust.js'

const HOUR = 60 * 60 * 1000
const DAY = 24 * HOUR
const CODE_TTL = 15 * 60 * 1000

function http(status, code) {
  const err = new Error(code)
  err.status = status
  err.code = code
  return err
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  })
}

function now(env) {
  return env.now ? env.now() : Date.now()
}

async function sha256(text) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function randomHex(bytes) {
  const a = new Uint8Array(bytes)
  crypto.getRandomValues(a)
  return [...a].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false
  let v = 0
  for (let i = 0; i < a.length; i++) v |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return v === 0
}

function clip(value, max) {
  return String(value || '').replace(/[\u0000-\u001f]/g, '').trim().slice(0, max)
}

async function readJson(request) {
  const text = await request.text()
  if (text.length > 3_000_000) throw http(413, 'too_large')
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    throw http(400, 'bad_json')
  }
}

function decodeB64(b64) {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i) & 255
  return out
}

function coarse(optIn, lat, lon) {
  if (!optIn) return { lat: null, lon: null }
  const la = Number(lat)
  const lo = Number(lon)
  if (!Number.isFinite(la) || !Number.isFinite(lo)) return { lat: null, lon: null }
  if (Math.abs(la) > 90 || Math.abs(lo) > 180) throw http(400, 'bad_location')
  return { lat: Math.round(la * 10) / 10, lon: Math.round(lo * 10) / 10 }
}

let matcherCache = null
function matcher(env) {
  const domainText = env.DOMAIN_TEXT || ''
  const abusedText = env.ABUSED_TEXT || ''
  if (!matcherCache || matcherCache.domainText !== domainText || matcherCache.abusedText !== abusedText) {
    matcherCache = { domainText, abusedText, value: createMatcher(domainText, abusedText) }
  }
  return matcherCache.value
}

function db(env) {
  return {
    async first(sql, ...args) {
      return env.DB.prepare(sql).bind(...args).first()
    },
    async all(sql, ...args) {
      const rows = await env.DB.prepare(sql).bind(...args).all()
      return rows.results || []
    },
    async run(sql, ...args) {
      return env.DB.prepare(sql).bind(...args).run()
    },
  }
}

function bearer(request) {
  const h = request.headers.get('authorization') || ''
  const m = /^Bearer\s+(\S+)$/i.exec(h)
  return m ? m[1] : ''
}

async function accountFrom(request, env) {
  const token = bearer(request)
  if (!token) return null
  const row = await db(env).first(
    `SELECT a.id, a.email, a.email_domain, a.created_at, a.verified_academic
     FROM session s JOIN account a ON a.id = s.account_id WHERE s.token_hash = ?`,
    await sha256(token),
  )
  return row || null
}

function num(v) {
  return Number(v || 0)
}

function ownerView(account, profile) {
  const anonymous = profile.visibility === 'anonymous'
  const verified = num(account.verified_academic) === 1
  return {
    id: account.id,
    email: account.email,
    displayName: profile.display_name,
    title: profile.title,
    affiliation: profile.affiliation,
    visibility: profile.visibility,
    license: profile.default_license,
    verifiedAcademic: verified,
    verifiedDomain: verified && !anonymous ? profile.verified_domain : null,
    photoUrl: profile.photo_key ? `/v1/media/avatars/${account.id}` : null,
  }
}

function publicContribution(row) {
  const anonymous = row.visibility === 'anonymous'
  const profilePublic = !anonymous && row.profile_visibility === 'public' && row.account_id
  const verified = profilePublic && num(row.verified_academic) === 1
  const share = num(row.share_location) === 1 && row.coarse_lat != null && row.coarse_lon != null
  return {
    id: row.id,
    kind: row.kind,
    species: row.species,
    regionId: row.region_id,
    body: row.body,
    rating: row.rating == null ? null : num(row.rating),
    license: row.license,
    licenseLabel: LICENSES[row.license] || row.license,
    credit: anonymous ? 'Anonymous' : (row.credit || 'Anonymous'),
    trustWeight: num(row.trust_weight),
    photoUrl: row.photo_key ? `/v1/media/photos/${row.id}` : null,
    createdAt: num(row.created_at),
    location: share ? { lat: num(row.coarse_lat), lon: num(row.coarse_lon) } : null,
    profile: profilePublic ? {
      id: row.account_id,
      displayName: row.display_name || '',
      title: row.title || '',
      affiliation: row.affiliation || '',
      verifiedAcademic: verified,
      verifiedDomain: verified ? row.verified_domain : null,
      photoUrl: row.avatar_key ? `/v1/media/avatars/${row.account_id}` : null,
    } : null,
  }
}

const CONTRIB_SELECT = `
  SELECT c.id, c.account_id, c.kind, c.species, c.region_id, c.body, c.rating, c.visibility,
         c.license, c.credit, c.status, c.trust_weight, c.photo_key, c.share_location,
         c.coarse_lat, c.coarse_lon, c.created_at,
         p.display_name, p.title, p.affiliation, p.visibility AS profile_visibility, p.photo_key AS avatar_key,
         p.verified_domain, a.verified_academic
  FROM contribution c
  LEFT JOIN profile p ON p.account_id = c.account_id
  LEFT JOIN account a ON a.id = c.account_id
`

async function loadProfile(env, accountId) {
  return db(env).first(`SELECT * FROM profile WHERE account_id = ?`, accountId)
}

export async function handle(request, env) {
  try {
    const url = new URL(request.url)
    const path = url.pathname.replace(/\/+$/, '') || '/'
    const method = request.method
    if (method === 'GET' && path === '/v1/health') {
      const emailConfigured = typeof env.MAILER === 'function' || Boolean(env.RESEND_API_KEY && env.EMAIL_FROM)
      return json({ ok: true, emailConfigured })
    }
    if (method === 'POST' && path === '/v1/auth/start') return await startAuth(request, env)
    if (method === 'POST' && path === '/v1/auth/verify') return await verifyAuth(request, env)
    if (method === 'GET' && path === '/v1/me') return await getMe(request, env)
    if (method === 'PATCH' && path === '/v1/me') return await patchMe(request, env)
    if (method === 'DELETE' && path === '/v1/me') return await deleteMe(request, env)
    if (method === 'GET' && path === '/v1/me/export') return await exportMe(request, env)
    if (method === 'POST' && path === '/v1/me/photo') return await putAvatar(request, env)
    if (method === 'POST' && path === '/v1/contributions') return await createContribution(request, env)
    if (method === 'GET' && path === '/v1/contributions') return await listPublic(url, env)
    const report = /^\/v1\/contributions\/([A-Za-z0-9]+)\/report$/.exec(path)
    if (report && method === 'POST') return await reportContribution(request, env, report[1])
    const photo = /^\/v1\/contributions\/([A-Za-z0-9]+)\/photo$/.exec(path)
    if (photo && method === 'POST') return await putContributionPhoto(request, env, photo[1])
    const media = /^\/v1\/media\/(avatars|photos)\/([A-Za-z0-9]+)$/.exec(path)
    if (media && method === 'GET') return await getMedia(env, media[1], media[2])
    const pub = /^\/v1\/profiles\/([A-Za-z0-9]+)$/.exec(path)
    if (pub && method === 'GET') return await publicProfile(env, pub[1])
    if (path === '/v1/admin/queue' && method === 'GET') return await adminQueue(request, env)
    const admin = /^\/v1\/admin\/contributions\/([A-Za-z0-9]+)$/.exec(path)
    if (admin && method === 'POST') return await adminAct(request, env, admin[1])
    return json({ error: 'not_found' }, 404)
  } catch (err) {
    const status = err.status || 500
    return json({ error: err.code || 'error' }, status)
  }
}

async function startAuth(request, env) {
  const body = await readJson(request)
  const email = String(body.email || '').trim().toLowerCase()
  if (!email.includes('@') || email.startsWith('@') || /\s/.test(email)) throw http(400, 'bad_email')
  const since = now(env) - HOUR
  const count = await db(env).first(`SELECT COUNT(*) AS n FROM otp WHERE email = ? AND created_at > ?`, email, since)
  if (num(count.n) >= 5) throw http(429, 'rate_limited')
  const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 100000000).padStart(8, '0')
  const salt = randomHex(16)
  const id = randomHex(16)
  const created = now(env)
  await db(env).run(
    `INSERT INTO otp (id, email, salt, code_hash, expires_at, attempts, used, created_at) VALUES (?,?,?,?,?,0,0,?)`,
    id, email, salt, await sha256(`${salt}:${code}`), created + CODE_TTL, created,
  )
  try {
    await deliverCode(env, email, code)
  } catch (err) {
    await db(env).run(`DELETE FROM otp WHERE id = ?`, id)
    throw err.status ? err : http(503, 'email_not_configured')
  }
  return json({ ok: true }, 202)
}

async function verifyAuth(request, env) {
  const body = await readJson(request)
  const email = String(body.email || '').trim().toLowerCase()
  const code = String(body.code || '').trim()
  const row = await db(env).first(
    `SELECT * FROM otp WHERE email = ? AND used = 0 ORDER BY created_at DESC LIMIT 1`,
    email,
  )
  if (!row) throw http(400, 'bad_code')
  if (num(row.expires_at) < now(env)) throw http(400, 'expired')
  if (num(row.attempts) >= 5) throw http(400, 'too_many_attempts')
  const hash = await sha256(`${row.salt}:${code}`)
  if (!safeEqual(hash, row.code_hash)) {
    await db(env).run(`UPDATE otp SET attempts = attempts + 1 WHERE id = ?`, row.id)
    throw http(400, 'bad_code')
  }
  await db(env).run(`UPDATE otp SET used = 1 WHERE id = ?`, row.id)
  const academic = matcher(env).check(email)
  let account = await db(env).first(`SELECT * FROM account WHERE email = ?`, email)
  if (!account) {
    const id = randomHex(16)
    await db(env).run(
      `INSERT INTO account (id, email, email_domain, created_at, verified_academic) VALUES (?,?,?,?,?)`,
      id, email, email.split('@')[1], now(env), academic.academic ? 1 : 0,
    )
    await db(env).run(
      `INSERT INTO profile (account_id, display_name, title, affiliation, visibility, default_license, verified_domain)
       VALUES (?,?,?,?, 'public', 'cc-by', ?)`,
      id, '', '', '', academic.academic ? academic.matchedDomain : null,
    )
    account = await db(env).first(`SELECT * FROM account WHERE id = ?`, id)
  } else if (academic.academic && num(account.verified_academic) !== 1) {
    await db(env).run(`UPDATE account SET verified_academic = 1 WHERE id = ?`, account.id)
    await db(env).run(`UPDATE profile SET verified_domain = ? WHERE account_id = ?`, academic.matchedDomain, account.id)
    account = await db(env).first(`SELECT * FROM account WHERE id = ?`, account.id)
  }
  const token = randomHex(32)
  await db(env).run(
    `INSERT INTO session (token_hash, account_id, created_at) VALUES (?,?,?)`,
    await sha256(token), account.id, now(env),
  )
  const profile = await loadProfile(env, account.id)
  return json({ token, profile: ownerView(account, profile) })
}

async function requireAccount(request, env) {
  const account = await accountFrom(request, env)
  if (!account) throw http(401, 'unauthorized')
  return account
}

async function getMe(request, env) {
  const account = await requireAccount(request, env)
  return json({ profile: ownerView(account, await loadProfile(env, account.id)) })
}

async function patchMe(request, env) {
  const account = await requireAccount(request, env)
  const body = await readJson(request)
  const profile = await loadProfile(env, account.id)
  const visibility = body.visibility == null ? profile.visibility : body.visibility
  if (visibility !== 'public' && visibility !== 'anonymous') throw http(400, 'bad_visibility')
  const license = normalizeLicense(body.license == null ? profile.default_license : body.license)
  if (!license) throw http(400, 'bad_license')
  const displayName = body.displayName == null ? profile.display_name : clip(body.displayName, 60)
  const title = body.title == null ? profile.title : clip(body.title, 80)
  const affiliation = body.affiliation == null ? profile.affiliation : clip(body.affiliation, 120)
  await db(env).run(
    `UPDATE profile SET display_name = ?, title = ?, affiliation = ?, visibility = ?, default_license = ? WHERE account_id = ?`,
    displayName, title, affiliation, visibility, license, account.id,
  )
  return json({ profile: ownerView(account, await loadProfile(env, account.id)) })
}

async function putAvatar(request, env) {
  const account = await requireAccount(request, env)
  const bytes = new Uint8Array(await request.arrayBuffer())
  if (bytes.length < 10 || bytes.length > 2_000_000) throw http(400, 'bad_photo')
  const key = `avatars/${account.id}.jpg`
  await env.PHOTOS.put(key, bytes)
  await db(env).run(`UPDATE profile SET photo_key = ? WHERE account_id = ?`, key, account.id)
  return json({ photoUrl: `/v1/media/avatars/${account.id}` })
}

async function createContribution(request, env) {
  const account = await requireAccount(request, env)
  const body = await readJson(request)
  const kind = body.kind
  if (kind !== 'review' && kind !== 'photo' && kind !== 'id_feedback') throw http(400, 'bad_kind')
  const species = clip(body.species, 200)
  const regionId = clip(body.regionId || body.region_id, 40)
  if (!species || !regionId) throw http(400, 'bad_subject')
  const profile = await loadProfile(env, account.id)
  const visibility = body.visibility || profile.visibility
  if (visibility !== 'public' && visibility !== 'anonymous') throw http(400, 'bad_visibility')
  const license = normalizeLicense(body.license || profile.default_license)
  if (!license) throw http(400, 'bad_license')
  const text = clip(body.body, 4000)
  let rating = body.rating == null || body.rating === '' ? null : Number(body.rating)
  if (rating != null && (!Number.isInteger(rating) || rating < 0 || rating > 5)) throw http(400, 'bad_rating')
  const anonymous = visibility === 'anonymous'
  const credit = anonymous ? 'Anonymous' : (clip(body.credit, 60) || clip(profile.display_name, 60) || 'Anonymous')
  // Anonymous rows do not keep a place, even if the client sent one.
  const where = anonymous
    ? { lat: null, lon: null }
    : coarse(Boolean(body.shareLocation), body.coarseLat ?? body.lat, body.coarseLon ?? body.lon)
  const ageDays = Math.floor((now(env) - num(account.created_at)) / DAY)
  const approvedPhotos = await db(env).first(
    `SELECT COUNT(*) AS n FROM contribution WHERE account_id = ? AND kind = 'photo' AND status = 'public'`,
    account.id,
  )
  const isNew = ageDays < 7 || num(approvedPhotos.n) < 1
  const status = kind === 'photo' && isNew ? 'pending' : 'public'
  const weight = trustWeight({
    verifiedAcademic: num(account.verified_academic) === 1,
    visibility,
    accountAgeDays: ageDays,
    kind,
    approved: status === 'public',
  })
  const id = randomHex(16)
  let photoKey = null
  if (body.photoBase64) {
    const bytes = decodeB64(body.photoBase64)
    if (bytes.length < 10 || bytes.length > 2_000_000) throw http(400, 'bad_photo')
    photoKey = `photos/${id}.jpg`
    await env.PHOTOS.put(photoKey, bytes)
  }
  await db(env).run(
    `INSERT INTO contribution (id, account_id, kind, species, region_id, body, rating, visibility, license, credit,
      status, trust_weight, photo_key, share_location, coarse_lat, coarse_lon, created_at, client_id)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    id, account.id, kind, species, regionId, text, rating, visibility, license, credit,
    status, weight, photoKey, where.lat == null ? 0 : 1, where.lat, where.lon, now(env), clip(body.clientId, 40) || null,
  )
  const row = await db(env).first(`${CONTRIB_SELECT} WHERE c.id = ?`, id)
  return json({ id, status, trustWeight: weight, contribution: publicContribution(row) }, 201)
}

async function listPublic(url, env) {
  const species = url.searchParams.get('species') || ''
  const region = url.searchParams.get('region') || ''
  const rows = await db(env).all(
    `${CONTRIB_SELECT}
     WHERE c.status = 'public' AND (? = '' OR c.species = ?) AND (? = '' OR c.region_id = ?)
     ORDER BY c.created_at DESC LIMIT 50`,
    species, species, region, region,
  )
  return json({ contributions: rows.map(publicContribution) })
}

async function reportContribution(request, env, id) {
  const body = await readJson(request)
  const reason = clip(body.reason, 500)
  if (reason.length < 3) throw http(400, 'bad_reason')
  const row = await db(env).first(`SELECT id, status FROM contribution WHERE id = ?`, id)
  if (!row) throw http(404, 'not_found')
  await db(env).run(
    `INSERT INTO report (id, contribution_id, reason, created_at) VALUES (?,?,?,?)`,
    randomHex(16), id, reason, now(env),
  )
  const count = await db(env).first(`SELECT COUNT(*) AS n FROM report WHERE contribution_id = ?`, id)
  let status = row.status
  if (num(count.n) >= 3 && status === 'public') {
    status = 'hidden'
    await db(env).run(`UPDATE contribution SET status = 'hidden' WHERE id = ?`, id)
  }
  return json({ ok: true, status, reports: num(count.n) })
}

async function putContributionPhoto(request, env, id) {
  const account = await requireAccount(request, env)
  const row = await db(env).first(`SELECT id, account_id FROM contribution WHERE id = ?`, id)
  if (!row || row.account_id !== account.id) throw http(404, 'not_found')
  const bytes = new Uint8Array(await request.arrayBuffer())
  if (bytes.length < 10 || bytes.length > 2_000_000) throw http(400, 'bad_photo')
  const key = `photos/${id}.jpg`
  await env.PHOTOS.put(key, bytes)
  await db(env).run(`UPDATE contribution SET photo_key = ? WHERE id = ?`, key, id)
  return json({ photoUrl: `/v1/media/photos/${id}` })
}

async function getMedia(env, bucket, id) {
  if (bucket === 'avatars') {
    const profile = await db(env).first(
      `SELECT photo_key, visibility FROM profile WHERE account_id = ?`,
      id,
    )
    if (!profile || profile.visibility !== 'public' || !profile.photo_key) throw http(404, 'not_found')
    const obj = await env.PHOTOS.get(profile.photo_key)
    if (!obj) throw http(404, 'not_found')
    return new Response(await obj.arrayBuffer(), { headers: { 'content-type': 'image/jpeg' } })
  }
  const row = await db(env).first(`SELECT photo_key, status FROM contribution WHERE id = ?`, id)
  if (!row || row.status !== 'public' || !row.photo_key) throw http(404, 'not_found')
  const obj = await env.PHOTOS.get(row.photo_key)
  if (!obj) throw http(404, 'not_found')
  return new Response(await obj.arrayBuffer(), { headers: { 'content-type': 'image/jpeg' } })
}

async function publicProfile(env, id) {
  const account = await db(env).first(`SELECT id, verified_academic FROM account WHERE id = ?`, id)
  const profile = account && await loadProfile(env, id)
  if (!account || !profile || profile.visibility !== 'public') throw http(404, 'not_found')
  const verified = num(account.verified_academic) === 1
  return json({
    id: account.id,
    displayName: profile.display_name,
    title: profile.title,
    affiliation: profile.affiliation,
    verifiedAcademic: verified,
    verifiedDomain: verified ? profile.verified_domain : null,
    photoUrl: profile.photo_key ? `/v1/media/avatars/${id}` : null,
  })
}

function requireAdmin(request, env) {
  const token = env.ADMIN_TOKEN ? String(env.ADMIN_TOKEN) : ''
  if (token.length < 8) throw http(503, 'admin_not_configured')
  if (!safeEqual(bearer(request), token)) throw http(401, 'unauthorized')
}

async function adminQueue(request, env) {
  requireAdmin(request, env)
  const rows = await db(env).all(
    `SELECT c.id, c.kind, c.species, c.region_id, c.credit, c.status, c.created_at,
            (SELECT COUNT(*) FROM report r WHERE r.contribution_id = c.id) AS reports
     FROM contribution c
     WHERE c.status IN ('pending', 'hidden')
     ORDER BY c.created_at ASC LIMIT 100`,
  )
  return json({
    queue: rows.map((r) => ({
      id: r.id,
      kind: r.kind,
      species: r.species,
      regionId: r.region_id,
      credit: r.credit,
      status: r.status,
      reports: num(r.reports),
      createdAt: num(r.created_at),
    })),
  })
}

async function adminAct(request, env, id) {
  requireAdmin(request, env)
  const body = await readJson(request)
  const row = await db(env).first(`${CONTRIB_SELECT} WHERE c.id = ?`, id)
  if (!row) throw http(404, 'not_found')
  if (body.action === 'hide') {
    await db(env).run(`UPDATE contribution SET status = 'hidden' WHERE id = ?`, id)
    return json({ id, status: 'hidden' })
  }
  if (body.action !== 'approve') throw http(400, 'bad_action')
  const account = row.account_id
    ? await db(env).first(`SELECT created_at, verified_academic FROM account WHERE id = ?`, row.account_id)
    : null
  const ageDays = account ? Math.floor((now(env) - num(account.created_at)) / DAY) : 0
  const weight = trustWeight({
    verifiedAcademic: account ? num(account.verified_academic) === 1 : false,
    visibility: row.visibility,
    accountAgeDays: ageDays,
    kind: row.kind,
    approved: true,
  })
  await db(env).run(`UPDATE contribution SET status = 'public', trust_weight = ? WHERE id = ?`, weight, id)
  return json({ id, status: 'public', trustWeight: weight })
}

async function deleteMe(request, env) {
  const account = await requireAccount(request, env)
  const pending = await db(env).all(
    `SELECT id, photo_key FROM contribution WHERE account_id = ? AND status != 'public'`,
    account.id,
  )
  for (const row of pending) {
    if (row.photo_key) await env.PHOTOS.delete(row.photo_key)
    await db(env).run(`DELETE FROM contribution WHERE id = ?`, row.id)
  }
  const profile = await loadProfile(env, account.id)
  if (profile?.photo_key) await env.PHOTOS.delete(profile.photo_key)
  await db(env).run(
    `UPDATE contribution SET account_id = NULL, visibility = 'anonymous', credit = 'Anonymous',
       trust_weight = 1, share_location = 0, coarse_lat = NULL, coarse_lon = NULL
     WHERE account_id = ?`,
    account.id,
  )
  await db(env).run(`DELETE FROM session WHERE account_id = ?`, account.id)
  await db(env).run(`DELETE FROM otp WHERE email = ?`, account.email)
  await db(env).run(`DELETE FROM profile WHERE account_id = ?`, account.id)
  await db(env).run(`DELETE FROM account WHERE id = ?`, account.id)
  return new Response(null, { status: 204 })
}

async function exportMe(request, env) {
  const account = await requireAccount(request, env)
  const profile = await loadProfile(env, account.id)
  const rows = await db(env).all(
    `SELECT id, kind, species, region_id, body, rating, visibility, license, credit, status, trust_weight,
            share_location, coarse_lat, coarse_lon, created_at
     FROM contribution WHERE account_id = ? ORDER BY created_at`,
    account.id,
  )
  return json({
    profile: ownerView(account, profile),
    contributions: rows.map((r) => ({
      id: r.id,
      kind: r.kind,
      species: r.species,
      regionId: r.region_id,
      body: r.body,
      rating: r.rating == null ? null : num(r.rating),
      visibility: r.visibility,
      license: r.license,
      credit: r.credit,
      status: r.status,
      trustWeight: num(r.trust_weight),
      shareLocation: num(r.share_location) === 1,
      coarseLat: r.coarse_lat == null ? null : num(r.coarse_lat),
      coarseLon: r.coarse_lon == null ? null : num(r.coarse_lon),
      createdAt: num(r.created_at),
    })),
  })
}
