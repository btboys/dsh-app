import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { createGateway } from './gateway.js'
import { DeviceStore } from './store.js'

test('an unpaired request is not forwarded to DSH', async () => {
  let forwarded = 0
  const upstream = createServer((req, res) => {
    forwarded += 1
    res.end('secret')
  })
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  const store = new DeviceStore(dir)
  const gateway = createGateway({
    store,
    upstreamOrigin: `http://127.0.0.1:${upstream.address().port}`,
    port: 0,
    workspaces: () => [],
  })
  const status = await gateway.start()
  // The gateway probes its upstream once at startup; only phone requests must be blocked.
  forwarded = 0
  const response = await fetch(`http://127.0.0.1:${status.port}/`)
  assert.equal(response.status, 401)
  assert.equal(forwarded, 0)
  await gateway.stop()
  await new Promise(resolve => upstream.close(resolve))
})

test('an authenticated homepage includes the mobile layout', async t => {
  const upstream = createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/html' })
    res.end('<html><head><title>DSH</title></head><body>Homepage</body></html>')
  })
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => upstream.close(resolve)))
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  let gateway
  t.after(async () => {
    try {
      if (gateway) await gateway.stop()
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })
  const store = new DeviceStore(dir)
  gateway = createGateway({ store, upstreamOrigin: `http://127.0.0.1:${upstream.address().port}`, port: 0 })
  const status = await gateway.start()
  const origin = `http://127.0.0.1:${status.port}`
  const { code } = store.issuePairing({ origin })
  const device = await store.pair({ code, origin })
  const response = await fetch(`${origin}/`, { headers: { authorization: `Bearer ${device.token}` }, signal: AbortSignal.timeout(10000) })
  assert.equal(response.status, 200)
  const html = await response.text()
  assert.match(html, /<title>DSH<\/title>/)
  assert.match(html, /<style>\s*@media \(max-width: 840px\)/)
  assert.match(html, /<script>[\s\S]*dsh-app-nav[\s\S]*<\/script>/)
  assert.match(html, /<body>Homepage<\/body>/)
})


test('synchronous listener can settle a fully initialized pending record once', async () => {
  const gateway = createGateway({ store: {}, upstreamOrigin: 'http://localhost', pendingTimeoutMs: 100 })
  let record
  gateway.events(item => {
    if (item.kind === 'pending-settled') return
    record = item
    assert.equal(typeof item.settle, 'function')
    item.settle('first')
    item.settle('second')
  })
  assert.equal(await gateway.publish({ kind: 'approval' }), 'first')
  assert.equal(record.settle('late'), false)
  await gateway.stop()
})

test('last listener removal settles pending and stop settles pending', async () => {
  const gateway = createGateway({ store: {}, upstreamOrigin: 'http://localhost', pendingTimeoutMs: 100 })
  const off = gateway.events(() => {})
  const first = gateway.publish({ kind: 'approval' })
  off()
  await assert.rejects(first, { reason: 'no_phone' })
  gateway.events(() => {})
  const second = gateway.publish({ kind: 'question' })
  await gateway.stop()
  await assert.rejects(second, { reason: 'no_phone' })
})

test('pending expires and rejects a late answer', async () => {
  const gateway = createGateway({ store: {}, upstreamOrigin: 'http://localhost', pendingTimeoutMs: 10 })
  let record
  gateway.events(item => { if (item.kind !== 'pending-settled') record = item })
  await assert.rejects(gateway.publish({ kind: 'approval' }), { reason: 'phone_timeout' })
  assert.equal(record.settle('late'), false)
  await gateway.stop()
})

test('answer beats timeout and later listener removal', async () => {
  const gateway = createGateway({ store: {}, upstreamOrigin: 'http://localhost', pendingTimeoutMs: 10 })
  let record
  const off = gateway.events(item => { if (item.kind !== 'pending-settled') record = item })
  const answer = gateway.publish({ kind: 'approval' })
  assert.equal(record.settle('allowed-once'), true)
  off()
  assert.equal(await answer, 'allowed-once')
  await new Promise(resolve => setTimeout(resolve, 20))
  assert.equal(record.settle('rejected'), false)
  await gateway.stop()
})


test('expired pending disappears from authenticated list and emits redacted terminal event', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const store = new DeviceStore(dir)
  const gateway = createGateway({ store, upstreamOrigin: 'http://localhost', pendingTimeoutMs: 100 })
  await gateway.start()
  t.after(() => gateway.stop())
  const origin = `http://127.0.0.1:${gateway.status().port}`
  const { code } = store.issuePairing({ origin })
  const device = await store.pair({ code, origin })
  const options = { headers: { authorization: `Bearer ${device.token}` } }
  const events = []
  gateway.events(event => events.push(event))
  const result = gateway.publish({ kind: 'approval', reason: 'sensitive', sessionId: 'secret' })
  const before = await (await fetch(`${origin}/dsh-app/approvals`, options)).json()
  assert.equal(before.pending.length, 1)
  const id = before.pending[0].id
  await assert.rejects(result, { reason: 'phone_timeout' })
  const after = await (await fetch(`${origin}/dsh-app/approvals`, options)).json()
  assert.deepEqual(after.pending, [])
  assert.deepEqual(events[1], { kind: 'pending-settled', id, state: 'phone_timeout' })
})

test('answer emits exactly one redacted terminal event', async () => {
  const gateway = createGateway({ store: {}, upstreamOrigin: 'http://localhost', pendingTimeoutMs: 100 })
  const events = []
  gateway.events(event => events.push(event))
  const result = gateway.publish({ kind: 'approval', reason: 'sensitive' })
  const record = events[0]
  assert.equal(record.settle('allowed-once'), true)
  assert.equal(record.settle('rejected'), false)
  assert.equal(await result, 'allowed-once')
  assert.deepEqual(events[1], { kind: 'pending-settled', id: record.id, state: 'answered' })
  assert.equal(events.length, 2)
  await gateway.stop()
})

test('a request body beyond the limit is refused instead of buffered', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const gateway = createGateway({ store: new DeviceStore(dir), upstreamOrigin: 'http://localhost', port: 0 })
  const status = await gateway.start()
  t.after(() => gateway.stop())
  const origin = `http://127.0.0.1:${status.port}`
  const response = await fetch(`${origin}/dsh-app/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: 'x', padding: 'a'.repeat(200 * 1024) }),
  })
  assert.equal(response.status, 413)
  assert.deepEqual(await response.json(), { error: 'payload_too_large' })
})

test('malformed JSON is a client error, not a crash', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const gateway = createGateway({ store: new DeviceStore(dir), upstreamOrigin: 'http://localhost', port: 0 })
  const status = await gateway.start()
  t.after(() => gateway.stop())
  const origin = `http://127.0.0.1:${status.port}`
  const response = await fetch(`${origin}/dsh-app/pair`, { method: 'POST', body: '{not json' })
  assert.equal(response.status, 400)
  assert.deepEqual(await response.json(), { error: 'invalid_json' })
})

async function pairedGateway(t, dir, workspaces) {
  const store = new DeviceStore(dir)
  const gateway = createGateway({ store, upstreamOrigin: 'http://localhost', port: 0, workspaces })
  const status = await gateway.start()
  t.after(() => gateway.stop())
  const origin = `http://127.0.0.1:${status.port}`
  const { code } = store.issuePairing({ origin })
  const device = await store.pair({ code, origin })
  return { origin, token: device.token }
}

test('a symlink inside the workspace cannot list or preview files outside it', async t => {
  const root = await mkdtemp(join(tmpdir(), 'dsh-app-root-'))
  const outside = await mkdtemp(join(tmpdir(), 'dsh-app-outside-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  t.after(() => rm(outside, { recursive: true, force: true }))
  await writeFile(join(outside, 'secret.txt'), 'secret')
  await symlink(join(outside, 'secret.txt'), join(root, 'leak.txt'))
  const workspaces = () => [{
    id: 'ws',
    title: 'ws',
    path: root,
    list: async target => ({ path: target, entries: [] }),
    read: target => readFile(target),
  }]
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const { origin, token } = await pairedGateway(t, dir, workspaces)
  const headers = { authorization: `Bearer ${token}` }
  const listed = await fetch(`${origin}/dsh-app/files?workspace=ws&path=.`, { headers })
  assert.equal(listed.status, 200)
  await listed.text()
  const preview = await fetch(`${origin}/dsh-app/files/preview?workspace=ws&path=leak.txt`, { headers })
  assert.equal(preview.status, 403)
  assert.deepEqual(await preview.json(), { error: 'outside_workspace' })
})

test('revoking a device closes the stream it already holds', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const store = new DeviceStore(dir)
  const gateway = createGateway({ store, upstreamOrigin: 'http://localhost', port: 0 })
  const status = await gateway.start()
  t.after(() => gateway.stop())
  const origin = `http://127.0.0.1:${status.port}`
  const { code } = store.issuePairing({ origin })
  const device = await store.pair({ code, origin })
  const controller = new AbortController()
  const stream = await fetch(`${origin}/dsh-app/events`, {
    headers: { authorization: `Bearer ${device.token}` },
    signal: controller.signal,
  })
  assert.equal(stream.status, 200)
  const reader = stream.body.getReader()
  assert.equal(gateway.disconnectDevice(device.id), 1)
  // The server ended the stream, so the reader reports the body as finished.
  let done = false
  while (!done) {
    const chunk = await reader.read()
    done = chunk.done
  }
  await store.revoke(device.id)
  controller.abort()
  const denied = await fetch(`${origin}/dsh-app/approvals`, { headers: { authorization: `Bearer ${device.token}` } })
  assert.equal(denied.status, 401)
})

test('diagnostics report the upstream they actually reached', async t => {
  const upstream = createServer((req, res) => res.end('ok'))
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => upstream.close(resolve)))
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const healthy = createGateway({
    store: new DeviceStore(dir),
    upstreamOrigin: `http://127.0.0.1:${upstream.address().port}`,
    port: 0,
  })
  const healthyStatus = await healthy.start()
  t.after(() => healthy.stop())
  assert.equal(healthyStatus.checks.find(check => check.name === 'upstream').ok, true)

  const broken = createGateway({ store: new DeviceStore(dir), upstreamOrigin: 'http://127.0.0.1:1', port: 0 })
  const brokenStatus = await broken.start()
  t.after(() => broken.stop())
  const check = brokenStatus.checks.find(item => item.name === 'upstream')
  assert.equal(check.ok, false)
  assert.equal(brokenStatus.checks.some(item => !item.ok), true)
})
