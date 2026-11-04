import assert from 'node:assert/strict'
import test from 'node:test'
import { Buffer } from 'node:buffer'
import { execFileSync } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { certificateSha256, createPairing, decideAccess, normalizeOrigin, parseBearer } from './protocol.js'

test('unpaired requests never reach DSH', () => {
  const decision = decideAccess({
    authorization: undefined,
    origin: 'https://192.168.1.8:3443',
    host: '192.168.1.8:3443',
    devices: [],
  })
  assert.equal(decision.allow, false)
  assert.equal(decision.reason, 'pairing_required')
})

test('a device token is accepted only for the origin it was paired with', () => {
  const devices = [{
    id: 'phone-1',
    token: 'token-lan',
    origin: 'https://192.168.1.8:3443',
    revoked: false,
  }]
  assert.equal(decideAccess({
    authorization: 'Bearer token-lan',
    origin: 'https://192.168.1.8:3443',
    host: '192.168.1.8:3443',
    devices,
  }).allow, true)
  const stolen = decideAccess({
    authorization: 'Bearer token-lan',
    origin: 'https://evil.example',
    host: 'evil.example',
    devices,
  })
  assert.equal(stolen.allow, false)
  assert.equal(stolen.reason, 'origin_mismatch')
})

test('revoked devices are rejected and the token is not echoed', () => {
  const decision = decideAccess({
    authorization: 'Bearer old-token',
    origin: 'https://mac.tailnet.ts.net',
    host: 'mac.tailnet.ts.net',
    devices: [{
      id: 'phone-1',
      token: 'old-token',
      origin: 'https://mac.tailnet.ts.net',
      revoked: true,
    }],
  })
  assert.equal(decision.allow, false)
  assert.equal(decision.reason, 'revoked')
  assert.equal(JSON.stringify(decision).includes('old-token'), false)
})

test('pairing binds a one-time code to one origin and never returns the code', () => {
  const now = 1_700_000_000_000
  const issued = createPairing({
    code: 'pair-code',
    origin: 'https://192.168.1.8:3443',
    now,
    ttlMs: 60_000,
  })
  const wrongOrigin = issued.redeem({ code: 'pair-code', origin: 'https://other.example', now: now + 1_000, deviceName: 'Pixel' })
  assert.equal(wrongOrigin.ok, false)
  assert.equal(wrongOrigin.reason, 'origin_mismatch')

  const ok = issued.redeem({ code: 'pair-code', origin: 'https://192.168.1.8:3443', now: now + 1_000, deviceName: 'Pixel' })
  assert.equal(ok.ok, true)
  assert.equal(ok.device.origin, 'https://192.168.1.8:3443')
  assert.equal(ok.device.name, 'Pixel')
  assert.equal(typeof ok.device.token, 'string')
  assert.equal(JSON.stringify(ok).includes('pair-code'), false)
})

test('a pairing code cannot be reused or redeemed after it expires', () => {
  const now = 1_700_000_000_000
  const issued = createPairing({ code: 'once', origin: 'https://mac.example', now, ttlMs: 1_000 })
  assert.equal(issued.redeem({ code: 'once', origin: 'https://mac.example', now: now + 1_001, deviceName: 'Phone' }).reason, 'expired')
  const fresh = createPairing({ code: 'once', origin: 'https://mac.example', now, ttlMs: 10_000 })
  assert.equal(fresh.redeem({ code: 'once', origin: 'https://mac.example', now: now + 1, deviceName: 'Phone' }).ok, true)
  assert.equal(fresh.redeem({ code: 'once', origin: 'https://mac.example', now: now + 2, deviceName: 'Phone' }).reason, 'used')
})

test('the WebView cookie is accepted only for its paired origin', () => {
  const devices = [{ id: 'phone-1', token: 'cookie-token', origin: 'https://192.168.1.8:3443', revoked: false }]
  assert.equal(decideAccess({
    cookie: 'dsh_app=cookie-token',
    origin: 'https://192.168.1.8:3443',
    host: '192.168.1.8:3443',
    devices,
  }).allow, true)
  assert.equal(decideAccess({
    cookie: 'dsh_app=cookie-token',
    origin: 'https://evil.example',
    host: 'evil.example',
    devices,
  }).reason, 'origin_mismatch')
})

test('the pairing fingerprint is the DER certificate Android will see', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-cert-'))
  const key = join(dir, 'key.pem')
  const cert = join(dir, 'cert.pem')
  const derPath = join(dir, 'cert.der')
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-keyout', key, '-out', cert, '-days', '1', '-nodes', '-subj', '/CN=dsh-app'])
  execFileSync('openssl', ['x509', '-in', cert, '-outform', 'der', '-out', derPath])
  const digest = execFileSync('openssl', ['dgst', '-sha256', '-binary', derPath])
  assert.equal(certificateSha256(execFileSync('openssl', ['x509', '-in', cert, '-outform', 'pem'])), Buffer.from(digest).toString('base64'))
})

test('origin comparison ignores default ports and trailing slashes', () => {
  assert.equal(normalizeOrigin('https://Mac.Example:443/'), 'https://mac.example')
  assert.equal(normalizeOrigin('http://127.0.0.1:80'), 'http://127.0.0.1')
  assert.equal(parseBearer('Bearer abc'), 'abc')
  assert.equal(parseBearer('Basic abc'), undefined)
})
