import { Buffer } from 'node:buffer'
import { createHash, randomBytes } from 'node:crypto'

const DEFAULT_PORTS = { http: '80', https: '443' }

export function certificateSha256(pem) {
  const body = pem.toString().replace(/-----BEGIN CERTIFICATE-----/g, '').replace(/-----END CERTIFICATE-----/g, '').replace(/\s/g, '')
  return createHash('sha256').update(Buffer.from(body, 'base64')).digest('base64')
}

export function normalizeOrigin(value) {
  const url = new URL(value)
  url.hash = ''
  url.search = ''
  url.pathname = ''
  url.hostname = url.hostname.toLowerCase()
  if (DEFAULT_PORTS[url.protocol.slice(0, -1)] === url.port) url.port = ''
  return url.origin
}

export function parseCookie(header, name) {
  if (typeof header !== 'string') return undefined
  for (const part of header.split(';')) {
    const [key, ...rest] = part.trim().split('=')
    if (key === name) return decodeURIComponent(rest.join('='))
  }
  return undefined
}

export function parseBearer(header) {
  if (typeof header !== 'string') return undefined
  const match = /^Bearer ([A-Za-z0-9._~+/-]+=*)$/.exec(header.trim())
  return match?.[1]
}

export function decideAccess({ authorization, cookie, origin, host, devices }) {
  const token = parseBearer(authorization) ?? parseCookie(cookie, 'dsh_app')
  if (!token) return deny('pairing_required')
  const device = devices.find(item => item.token === token)
  if (!device) return deny('authentication_failed')
  if (device.revoked) return deny('revoked')
  if (!sameOrigin(device.origin, origin, host)) return deny('origin_mismatch')
  return { allow: true, deviceId: device.id }
}

export function createPairing({ code, origin, now, ttlMs }) {
  const bound = normalizeOrigin(origin)
  let used = false
  return {
    origin: bound,
    expiresAt: now + ttlMs,
    redeem({ code: presented, origin: requestOrigin, now: at, deviceName }) {
      if (used) return fail('used')
      if (at > now + ttlMs) return fail('expired')
      if (presented !== code) return fail('invalid_code')
      if (normalizeOrigin(requestOrigin) !== bound) return fail('origin_mismatch')
      used = true
      return {
        ok: true,
        device: {
          id: `dev_${randomBytes(8).toString('hex')}`,
          name: cleanName(deviceName),
          token: randomBytes(32).toString('base64url'),
          origin: bound,
          revoked: false,
          pairedAt: at,
        },
      }
    },
  }
}

function sameOrigin(expected, origin, host) {
  const left = normalizeOrigin(expected)
  if (origin && normalizeOrigin(origin) === left) return true
  if (!host) return false
  try {
    return normalizeOrigin(`${new URL(expected).protocol}//${host}`) === left
  } catch {
    return false
  }
}

function cleanName(value) {
  const name = String(value ?? '').trim().slice(0, 40)
  return name || 'Android'
}

function deny(reason) {
  return { allow: false, reason }
}

function fail(reason) {
  return { ok: false, reason }
}
