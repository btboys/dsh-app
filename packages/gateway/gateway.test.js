import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
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
  const response = await fetch(`http://127.0.0.1:${status.port}/`)
  assert.equal(response.status, 401)
  assert.equal(forwarded, 0)
  await gateway.stop()
  await new Promise(resolve => upstream.close(resolve))
})
