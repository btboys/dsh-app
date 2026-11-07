import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { createGateway } from './gateway.js'
import { DeviceStore } from './store.js'
import { certificateSha256 } from './protocol.js'
import { loadOrCreateCertificate } from './tls.js'

function originOf(status) {
  return `https://127.0.0.1:${status.port}`
}

async function tlsGateway(t) {
  const upstream = createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/html' })
    res.end('<html><head></head><body>Homepage</body></html>')
  })
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => upstream.close(resolve)))
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const tls = await loadOrCreateCertificate(join(dir, 'tls'), { hosts: ['127.0.0.1'] })
  const store = new DeviceStore(dir)
  const gateway = createGateway({
    store,
    tls,
    upstreamOrigin: `http://127.0.0.1:${upstream.address().port}`,
    port: 0,
    workspaces: () => [],
  })
  t.after(() => gateway.stop())
  const status = await gateway.start()
  return { gateway, status, store, tls, dir }
}

function get(status, path, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = httpsRequest({
      host: '127.0.0.1',
      port: status.port,
      path,
      headers,
      rejectUnauthorized: false,
    }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }))
    })
    req.on('error', reject)
    req.end()
  })
}

test('the gateway serves TLS and reports the fingerprint the phone must verify', async t => {
  const { gateway, status, tls } = await tlsGateway(t)
  const expected = certificateSha256(tls.cert)
  assert.equal(status.certificateSha256, expected)
  assert.match(status.origins[0], /^https:\/\//)
  const response = await get(status, '/dsh-app/discover')
  assert.equal(response.status, 200)
  assert.match(response.body, /dsh-app/)
})

test('the pairing response carries the certificate fingerprint, never the key', async t => {
  const { gateway, status, tls } = await tlsGateway(t)
  const expected = certificateSha256(tls.cert)
  const pairing = gateway.pairing(originOf(status))
  assert.equal(pairing.certificateSha256, expected)
  assert.equal(JSON.stringify(pairing).includes('PRIVATE KEY'), false)
  assert.equal(typeof pairing.code, 'string')
})

test('the pairing fingerprint equals the DER hash Android computes from the socket', async t => {
  const { status, tls } = await tlsGateway(t)
  const presented = await new Promise((resolve, reject) => {
    const req = httpsRequest({ host: '127.0.0.1', port: status.port, path: '/dsh-app/discover', rejectUnauthorized: false }, res => {
      const cert = res.socket.getPeerCertificate()
      res.resume()
      resolve(cert)
    })
    req.on('error', reject)
    req.end()
  })
  const { createHash } = await import('node:crypto')
  const fromSocket = createHash('sha256').update(presented.raw).digest('base64')
  assert.equal(fromSocket, certificateSha256(tls.cert))
})
