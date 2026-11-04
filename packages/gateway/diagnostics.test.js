import assert from 'node:assert/strict'
import test from 'node:test'
import { diagnose } from './diagnostics.js'

test('diagnostics name the first broken hop without secrets', () => {
  const report = diagnose({
    upstreamOrigin: 'http://127.0.0.1:3080',
    upstreamReachable: false,
    listening: true,
    origins: ['https://192.168.1.8:3443'],
    pairedDevices: 1,
    token: 'secret-token',
  })
  assert.equal(report.ok, false)
  assert.equal(report.failing, 'upstream')
  assert.equal(JSON.stringify(report).includes('secret-token'), false)
  assert.equal(report.checks.find(item => item.name === 'pairing').ok, true)
})

test('a healthy gateway reports every hop ok', () => {
  const report = diagnose({
    upstreamOrigin: 'http://127.0.0.1:3080',
    upstreamReachable: true,
    listening: true,
    origins: ['https://192.168.1.8:3443', 'https://mac.example'],
    pairedDevices: 2,
  })
  assert.equal(report.ok, true)
  assert.equal(report.failing, undefined)
  assert.equal(report.checks.every(item => item.ok), true)
})
