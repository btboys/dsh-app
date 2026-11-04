import assert from 'node:assert/strict'
import { mkdtemp, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { DeviceStore } from './store.js'

test('pairing survives a restart and revoke removes the token', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  const first = new DeviceStore(dir)
  const issued = first.issuePairing({ origin: 'https://192.168.1.9:3443', now: 1_000 })
  const device = await first.pair({
    code: issued.code,
    origin: issued.origin,
    deviceName: 'Pixel',
    now: 1_000,
  })
  assert.equal(device.origin, 'https://192.168.1.9:3443')

  const reloaded = new DeviceStore(dir)
  await reloaded.load()
  assert.equal(reloaded.access({ authorization: `Bearer ${device.token}`, origin: device.origin }).allow, true)
  await reloaded.revoke(device.id)
  assert.equal(reloaded.access({ authorization: `Bearer ${device.token}`, origin: device.origin }).reason, 'revoked')

  const raw = await readFile(join(dir, 'devices.json'), 'utf8')
  assert.equal(raw.includes(issued.code), false)
  assert.equal(raw.includes(device.token), true)
})

test('a public file response never includes a device token', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-app-'))
  const store = new DeviceStore(dir)
  const issued = store.issuePairing({ origin: 'https://mac.example', now: 1 })
  await store.pair({ code: issued.code, origin: issued.origin, deviceName: 'Phone', now: 1 })
  const status = store.publicStatus()
  assert.equal(JSON.stringify(status).includes(issued.code), false)
  assert.equal(status.devices[0].token, undefined)
  assert.equal(status.devices[0].name, 'Phone')
})
