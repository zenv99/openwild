import { normalizeLicense, LICENSES } from './licenses.js'

const HOUR = 60 * 60 * 1000
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

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

function num(v) {
  return Number(v || 0)
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

function installId(request, body) {
  const id = String(request.headers.get('x-install-id') || body?.installId || '').trim()
  if (!UUID.test(id)) throw http(400, 'bad_install')
  return id
}

function publicItem(row) {
  const anonymous = row.credit === 'Anonymous'
  const avatarPublic = !anonymous && row.avatar_status === 'public' && row.avatar_key
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
    credit: anonymous ? 'Anonymous' : row.credit,
    title: anonymous ? '' : row.title,
    affiliation: anonymous ? '' : row.affiliation,
    trustWeight: 1,
    photoUrl: row.photo_key && row.status === 'public' ? `/v1/media/photos/${row.id}` : null,
    avatarUrl: avatarPublic ? `/v1/media/avatars/${row.id}` : null,
    createdAt: num(row.created_at),
    location: share ? { lat: num(row.coarse_lat), lon: num(row.coarse_lon) } : null,
  }
}

export async function handle(request, env) {
  try {
    const url = new URL(request.url)
    const path = url.pathname.replace(/\/+$/, '') || '/'
    const method = request.method
    if (method === 'GET' && path === '/v1/health') return json({ ok: true, accounts: false })
    if (method === 'POST' && path === '/v1/submissions') return await createSubmission(request, env)
    if (method === 'GET' && path === '/v1/submissions') return await listPublic(url, env)
    const one = /^\/v1\/submissions\/([A-Za-z0-9]+)$/.exec(path)
    if (one && method === 'DELETE') return await deleteOwn(request, env, one[1])
    const report = /^\/v1\/submissions\/([A-Za-z0-9]+)\/report$/.exec(path)
    if (report && method === 'POST') return await reportSubmission(request, env, report[1])
    const media = /^\/v1\/media\/(photos|avatars)\/([A-Za-z0-9]+)$/.exec(path)
    if (media && method === 'GET') return await getMedia(env, media[1], media[2])
    if (path === '/v1/admin/queue' && method === 'GET') return await adminQueue(request, env)
    const admin = /^\/v1\/admin\/submissions\/([A-Za-z0-9]+)$/.exec(path)
    if (admin && method === 'POST') return await adminAct(request, env, admin[1])
    return json({ error: 'not_found' }, 404)
  } catch (err) {
    return json({ error: err.code || 'error' }, err.status || 500)
  }
}

async function createSubmission(request, env) {
  const body = await readJson(request)
  const install = installId(request, body)
  const since = now(env) - HOUR
  const count = await db(env).first(
    `SELECT COUNT(*) AS n FROM submission WHERE install_id = ? AND created_at > ?`,
    install, since,
  )
  if (num(count.n) >= 30) throw http(429, 'rate_limited')
  const kind = body.kind
  if (kind !== 'review' && kind !== 'photo' && kind !== 'id_feedback') throw http(400, 'bad_kind')
  const species = clip(body.species, 200)
  const regionId = clip(body.regionId || body.region_id, 40)
  if (!species || !regionId) throw http(400, 'bad_subject')
  const license = normalizeLicense(body.license)
  if (!license) throw http(400, 'bad_license')
  const credit = clip(body.credit || body.name, 60) || 'Anonymous'
  const anonymous = credit === 'Anonymous'
  const title = anonymous ? '' : clip(body.title, 80)
  const affiliation = anonymous ? '' : clip(body.affiliation, 120)
  let rating = body.rating == null || body.rating === '' ? null : Number(body.rating)
  if (rating != null && (!Number.isInteger(rating) || rating < 0 || rating > 5)) throw http(400, 'bad_rating')
  const where = anonymous ? { lat: null, lon: null } : coarse(Boolean(body.shareLocation), body.coarseLat ?? body.lat, body.coarseLon ?? body.lon)
  const id = randomHex(16)
  let photoKey = null
  if (body.photoBase64) {
    const bytes = decodeB64(body.photoBase64)
    if (bytes.length < 10 || bytes.length > 2_000_000) throw http(400, 'bad_photo')
    photoKey = `photos/${id}.jpg`
    await env.PHOTOS.put(photoKey, bytes)
  }
  let avatarKey = null
  let avatarStatus = null
  if (!anonymous && body.avatarBase64) {
    const bytes = decodeB64(body.avatarBase64)
    if (bytes.length < 10 || bytes.length > 200_000) throw http(400, 'bad_avatar')
    avatarKey = `avatars/${id}.jpg`
    avatarStatus = 'pending'
    await env.PHOTOS.put(avatarKey, bytes)
  }
  const status = kind === 'photo' ? 'pending' : 'public'
  await db(env).run(
    `INSERT INTO submission (id, install_id, kind, species, region_id, body, rating, license, credit, title, affiliation,
      status, photo_key, avatar_key, avatar_status, share_location, coarse_lat, coarse_lon, created_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    id, install, kind, species, regionId, clip(body.body, 4000), rating, license, credit, title, affiliation,
    status, photoKey, avatarKey, avatarStatus, where.lat == null ? 0 : 1, where.lat, where.lon, now(env),
  )
  const row = await db(env).first(`SELECT * FROM submission WHERE id = ?`, id)
  return json({ id, status, avatarStatus, trustWeight: 1, submission: publicItem(row) }, 201)
}

async function listPublic(url, env) {
  const species = url.searchParams.get('species') || ''
  const region = url.searchParams.get('region') || ''
  const rows = await db(env).all(
    `SELECT * FROM submission WHERE status = 'public' AND (? = '' OR species = ?) AND (? = '' OR region_id = ?)
     ORDER BY created_at DESC LIMIT 50`,
    species, species, region, region,
  )
  return json({ submissions: rows.map(publicItem) })
}

async function deleteOwn(request, env, id) {
  const body = request.headers.get('content-type')?.includes('json') ? await readJson(request) : {}
  const install = installId(request, body)
  const row = await db(env).first(`SELECT * FROM submission WHERE id = ?`, id)
  if (!row) throw http(404, 'not_found')
  if (row.install_id !== install) throw http(403, 'forbidden')
  if (row.photo_key) await env.PHOTOS.delete(row.photo_key)
  if (row.avatar_key) await env.PHOTOS.delete(row.avatar_key)
  await db(env).run(`DELETE FROM report WHERE submission_id = ?`, id)
  await db(env).run(`DELETE FROM submission WHERE id = ?`, id)
  return new Response(null, { status: 204 })
}

async function reportSubmission(request, env, id) {
  const body = await readJson(request)
  const reason = clip(body.reason, 500)
  if (reason.length < 3) throw http(400, 'bad_reason')
  const row = await db(env).first(`SELECT id, status FROM submission WHERE id = ?`, id)
  if (!row) throw http(404, 'not_found')
  await db(env).run(
    `INSERT INTO report (id, submission_id, reason, created_at) VALUES (?,?,?,?)`,
    randomHex(16), id, reason, now(env),
  )
  const count = await db(env).first(`SELECT COUNT(*) AS n FROM report WHERE submission_id = ?`, id)
  let status = row.status
  if (num(count.n) >= 3 && status === 'public') {
    status = 'hidden'
    await db(env).run(`UPDATE submission SET status = 'hidden', avatar_status = CASE WHEN avatar_status IS NULL THEN NULL ELSE 'hidden' END WHERE id = ?`, id)
  }
  return json({ ok: true, status, reports: num(count.n) })
}

async function getMedia(env, bucket, id) {
  const row = await db(env).first(`SELECT * FROM submission WHERE id = ?`, id)
  if (!row || row.status !== 'public') throw http(404, 'not_found')
  if (bucket === 'avatars') {
    if (row.credit === 'Anonymous' || row.avatar_status !== 'public' || !row.avatar_key) throw http(404, 'not_found')
    const obj = await env.PHOTOS.get(row.avatar_key)
    if (!obj) throw http(404, 'not_found')
    return new Response(await obj.arrayBuffer(), { headers: { 'content-type': 'image/jpeg' } })
  }
  if (!row.photo_key) throw http(404, 'not_found')
  const obj = await env.PHOTOS.get(row.photo_key)
  if (!obj) throw http(404, 'not_found')
  return new Response(await obj.arrayBuffer(), { headers: { 'content-type': 'image/jpeg' } })
}

function requireAdmin(request, env) {
  const token = env.ADMIN_TOKEN ? String(env.ADMIN_TOKEN) : ''
  if (token.length < 8) throw http(503, 'admin_not_configured')
  const header = request.headers.get('authorization') || ''
  const got = header.replace(/^Bearer\s+/i, '')
  if (!safeEqual(got, token)) throw http(401, 'unauthorized')
}

async function adminQueue(request, env) {
  requireAdmin(request, env)
  const rows = await db(env).all(
    `SELECT id, kind, species, region_id, credit, status, avatar_status, created_at,
            (SELECT COUNT(*) FROM report r WHERE r.submission_id = submission.id) AS reports
     FROM submission
     WHERE status = 'pending' OR avatar_status = 'pending'
     ORDER BY created_at ASC LIMIT 100`,
  )
  return json({
    queue: rows.map((r) => ({
      id: r.id,
      kind: r.kind,
      species: r.species,
      regionId: r.region_id,
      credit: r.credit,
      status: r.status,
      avatarStatus: r.avatar_status,
      reports: num(r.reports),
      createdAt: num(r.created_at),
    })),
  })
}

async function adminAct(request, env, id) {
  requireAdmin(request, env)
  const body = await readJson(request)
  const row = await db(env).first(`SELECT * FROM submission WHERE id = ?`, id)
  if (!row) throw http(404, 'not_found')
  if (body.action === 'hide') {
    await db(env).run(
      `UPDATE submission SET status = 'hidden', avatar_status = CASE WHEN avatar_status IS NULL THEN NULL ELSE 'hidden' END WHERE id = ?`,
      id,
    )
    return json({ id, status: 'hidden' })
  }
  if (body.action !== 'approve') throw http(400, 'bad_action')
  const avatarStatus = row.avatar_key ? 'public' : null
  await db(env).run(`UPDATE submission SET status = 'public', avatar_status = ? WHERE id = ?`, avatarStatus, id)
  return json({ id, status: 'public', avatarStatus, trustWeight: 1 })
}
