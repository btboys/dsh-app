import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { createHash } from 'node:crypto'
import { X509Certificate } from 'node:crypto'
import { certificateSha256 } from './protocol.js'
import { lanAddresses, loadOrCreateCertificate, opensslConfig } from './tls.js'

async function identity(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-tls-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  return { dir, create: () => loadOrCreateCertificate(dir, { hosts: ['192.168.1.10'] }) }
}

test('the gateway identity is created once and reused', async t => {
  const { create } = await identity(t)
  const first = await create()
  const second = await create()
  assert.equal(certificateSha256(first.cert), certificateSha256(second.cert))
  assert.equal(first.key.toString(), second.key.toString())
})

test('the stored key and directory are not readable by other users', async t => {
  const { dir, create } = await identity(t)
  await create()
  assert.equal((await stat(join(dir, 'key.pem'))).mode & 0o777, 0o600)
  assert.equal((await stat(dir)).mode & 0o777, 0o700)
})

test('the fingerprint is the base64 SHA-256 of the DER the phone will see', async t => {
  const { create } = await identity(t)
  const { cert } = await create()
  const parsed = new X509Certificate(cert)
  const expected = createHash('sha256').update(Buffer.from(parsed.raw)).digest('base64')
  assert.equal(certificateSha256(cert), expected)
  assert.match(certificateSha256(cert), /^[A-Za-z0-9+/]{43}=$/)
})

test('the certificate names the LAN addresses it is served from', async t => {
  const { create } = await identity(t)
  const { cert } = await create()
  const parsed = new X509Certificate(cert)
  assert.match(parsed.subjectAltName, /DNS:localhost/)
  assert.match(parsed.subjectAltName, /IP Address:127\.0\.0\.1/)
  assert.match(parsed.subjectAltName, /IP Address:192\.168\.1\.10/)
  assert.equal(parsed.ca, false)
})

test('a certificate without its key fails closed instead of regenerating', async t => {
  const { dir, create } = await identity(t)
  await create()
  await writeFile(join(dir, 'key.pem'), '', { mode: 0o600 })
  await rm(join(dir, 'key.pem'))
  await assert.rejects(create(), /incomplete gateway identity/)
  // The surviving certificate is untouched, so a later repair keeps the same identity.
  const rest = await create().catch(error => error)
  assert.ok(rest instanceof Error)
})

test('a key without its certificate fails closed', async t => {
  const { dir, create } = await identity(t)
  await create()
  await rm(join(dir, 'cert.pem'))
  await assert.rejects(create(), /incomplete gateway identity/)
})

test('a corrupted certificate is a startup failure', async t => {
  const { dir, create } = await identity(t)
  await create()
  await writeFile(join(dir, 'cert.pem'), 'not a certificate')
  await assert.rejects(create(), /unreadable gateway certificate/)
})

test('a certificate from another identity is a startup failure', async t => {
  const first = await identity(t)
  const second = await identity(t)
  await first.create()
  await second.create()
  const foreign = await readFile(join(second.dir, 'cert.pem'))
  await writeFile(join(first.dir, 'cert.pem'), foreign)
  await assert.rejects(first.create(), /key does not match its certificate/)
})

test('openssl names every loopback and LAN address exactly once', () => {
  const config = opensslConfig(['192.168.1.10', '192.168.1.10', '10.0.0.4', '127.0.0.1'])
  const san = config.split('subjectAltName = ')[1].split('\n')[0]
  assert.equal(san, 'DNS:localhost,IP:127.0.0.1,IP:192.168.1.10,IP:10.0.0.4')
  assert.match(config, /basicConstraints = CA:FALSE/)
  assert.equal(lanAddresses().includes('127.0.0.1'), false)
})
