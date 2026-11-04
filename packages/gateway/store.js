import { randomBytes } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { decideAccess, normalizeOrigin } from './protocol.js'

export class DeviceStore {
  constructor(dir) {
    this.dir = dir
    this.file = join(dir, 'devices.json')
    this.devices = []
    this.pairing = undefined
  }

  async load() {
    await mkdir(this.dir, { recursive: true })
    try {
      const parsed = JSON.parse(await readFile(this.file, 'utf8'))
      this.devices = Array.isArray(parsed.devices) ? parsed.devices : []
    } catch (error) {
      if (error.code !== 'ENOENT') throw error
      this.devices = []
    }
  }

  issuePairing({ origin, now = Date.now(), ttlMs = 5 * 60_000 }) {
    const code = randomBytes(18).toString('base64url')
    this.pairing = { code, origin: normalizeOrigin(origin), expiresAt: now + ttlMs }
    return { code, origin: this.pairing.origin, expiresAt: this.pairing.expiresAt }
  }

  async pair({ code, origin, deviceName, now = Date.now() }) {
    if (!this.pairing) throw Object.assign(new Error('pairing_required'), { reason: 'pairing_required' })
    if (now > this.pairing.expiresAt) throw Object.assign(new Error('expired'), { reason: 'expired' })
    if (code !== this.pairing.code) throw Object.assign(new Error('invalid_code'), { reason: 'invalid_code' })
    if (normalizeOrigin(origin) !== this.pairing.origin) {
      throw Object.assign(new Error('origin_mismatch'), { reason: 'origin_mismatch' })
    }
    const device = {
      id: `dev_${randomBytes(8).toString('hex')}`,
      name: String(deviceName ?? '').trim().slice(0, 40) || 'Android',
      token: randomBytes(32).toString('base64url'),
      origin: this.pairing.origin,
      revoked: false,
      pairedAt: now,
    }
    this.pairing = undefined
    this.devices.push(device)
    await this.save()
    return device
  }

  access(request) {
    return decideAccess({ ...request, devices: this.devices })
  }

  async revoke(id) {
    const device = this.devices.find(item => item.id === id)
    if (!device) return false
    device.revoked = true
    await this.save()
    return true
  }

  publicStatus() {
    return {
      devices: this.devices.map(device => ({
        id: device.id,
        name: device.name,
        origin: device.origin,
        revoked: device.revoked,
        pairedAt: device.pairedAt,
      })),
    }
  }

  async save() {
    const body = JSON.stringify({ devices: this.devices }, null, 2)
    const temporary = `${this.file}.tmp`
    await writeFile(temporary, body)
    await rename(temporary, this.file)
  }
}
