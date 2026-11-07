import { createSocket } from 'node:dgram'
import { createServer as createHttpServer, request as httpRequest } from 'node:http'
import { createServer as createHttpsServer, request as httpsRequest } from 'node:https'
import { randomBytes } from 'node:crypto'
import { networkInterfaces } from 'node:os'
import { diagnose } from './diagnostics.js'
import { previewFile, safeRealChild } from './files.js'
import { mobileScript, mobileStyle } from './layout.js'
import { certificateSha256, normalizeOrigin, parseBearer } from './protocol.js'

const TEXT_LIMIT = 64 * 1024
const IMAGE_LIMIT = 2 * 1024 * 1024

export function createGateway({ store, tls, upstreamOrigin, port = 0, remoteOrigins = [], workspaces = () => [], pendingTimeoutMs = 60_000 }) {
  const pending = new Map()
  const listeners = new Set()
  const eventStreams = new Map()
  let configuredOrigins = [...remoteOrigins]
  let server
  let beacon
  let listening = false

  function origins() {
    const scheme = tls ? 'https' : 'http'
    const local = lanHosts().map(host => `${scheme}://${host}:${boundPort()}`)
    return [...new Set([...local, ...configuredOrigins.map(normalizeOrigin)])]
  }

  function boundPort() {
    return server?.address()?.port ?? port
  }

  function status() {
    return {
      listening,
      port: boundPort(),
      upstreamOrigin,
      origins: origins(),
      certificateSha256: tls ? certificateSha256(tls.cert) : undefined,
      ...store.publicStatus(),
      checks: diagnose({
        upstreamOrigin,
        upstreamReachable: true,
        listening,
        origins: origins(),
        pairedDevices: store.publicStatus().devices.length,
      }).checks,
    }
  }

  async function handler(req, res) {
    try {
      await route(req, res)
    } catch (error) {
      if (res.headersSent) {
        res.destroy()
        return
      }
      if (error.status) {
        send(res, error.status, { error: error.code ?? 'request_failed' })
        return
      }
      send(res, 500, { error: 'internal_error' })
    }
  }

  async function route(req, res) {
    const url = new URL(req.url ?? '/', `https://${req.headers.host ?? 'localhost'}`)
    if (req.method === 'GET' && url.pathname === '/dsh-app/discover') {
      return send(res, 200, { name: 'dsh-app', origins: origins(), port: boundPort() })
    }
    if (req.method === 'POST' && url.pathname === '/dsh-app/pair') {
      const body = await readJson(req)
      try {
        const device = await store.pair({
          code: body.code,
          origin: `${tls ? 'https' : 'http'}://${req.headers.host}`,
          deviceName: body.name,
        })
        return send(res, 200, {
          id: device.id,
          token: device.token,
          origin: device.origin,
          name: device.name,
          certificateSha256: tls ? certificateSha256(tls.cert) : undefined,
        })
      } catch (error) {
        return send(res, 401, { error: error.reason ?? 'authentication_failed' })
      }
    }
    const decision = store.access({
      authorization: req.headers.authorization,
      cookie: req.headers.cookie,
      origin: req.headers.origin,
      host: req.headers.host,
    })
    if (!decision.allow) return send(res, 401, { error: decision.reason })
    if (req.method === 'GET' && url.pathname === '/dsh-app/status') return send(res, 200, status())
    if (req.method === 'GET' && url.pathname === '/dsh-app/diagnostics') {
      return send(res, 200, diagnose({
        upstreamOrigin,
        upstreamReachable: true,
        listening,
        origins: origins(),
        pairedDevices: store.publicStatus().devices.length,
      }))
    }
    if (req.method === 'POST' && url.pathname === '/dsh-app/session') {
      res.writeHead(204, {
        'set-cookie': `dsh_app=${encodeURIComponent(parseBearer(req.headers.authorization) ?? '')}; Path=/; ${tls ? 'Secure; ' : ''}HttpOnly; SameSite=Strict`,
        'cache-control': 'no-store',
      })
      res.end()
      return
    }
    if (req.method === 'GET' && url.pathname === '/dsh-app/events') return attachEvents(req, res, decision.deviceId)
    if (req.method === 'GET' && url.pathname === '/dsh-app/approvals') {
      return send(res, 200, { pending: [...pending.values()] })
    }
    if (req.method === 'POST' && url.pathname === '/dsh-app/approvals/answer') {
      const body = await readJson(req)
      const item = pending.get(body.id)
      if (!item || item.kind !== 'approval') return send(res, 404, { error: 'not_found' })
      if (!item.settle(body.outcome)) return send(res, 404, { error: 'not_found' })
      return send(res, 200, { ok: true })
    }
    if (req.method === 'POST' && url.pathname === '/dsh-app/questions/answer') {
      const body = await readJson(req)
      const item = pending.get(body.id)
      if (!item || item.kind !== 'question') return send(res, 404, { error: 'not_found' })
      if (!item.settle(body.answer)) return send(res, 404, { error: 'not_found' })
      return send(res, 200, { ok: true })
    }
    if (req.method === 'GET' && url.pathname === '/dsh-app/workspaces') {
      return send(res, 200, { workspaces: workspaces().map(item => ({ id: item.id, title: item.title ?? item.path })) })
    }
    if (req.method === 'GET' && url.pathname === '/dsh-app/files') {
      const workspace = workspaces().find(item => item.id === url.searchParams.get('workspace'))
      if (!workspace) return send(res, 404, { error: 'workspace_not_found' })
      const target = await safeRealChild(workspace.path, url.searchParams.get('path') || '.')
      if (!target) return send(res, 403, { error: 'outside_workspace' })
      const listing = await workspace.list(target)
      return send(res, 200, listing)
    }
    if (req.method === 'GET' && url.pathname === '/dsh-app/files/preview') {
      const workspace = workspaces().find(item => item.id === url.searchParams.get('workspace'))
      if (!workspace) return send(res, 404, { error: 'workspace_not_found' })
      const target = await safeRealChild(workspace.path, url.searchParams.get('path') || '')
      if (!target) return send(res, 403, { error: 'outside_workspace' })
      const bytes = await workspace.read(target)
      const preview = previewFile({ path: target, bytes, limit: TEXT_LIMIT })
      if (preview.kind === 'image' && bytes.length > IMAGE_LIMIT) return send(res, 413, { error: 'too_large' })
      if (preview.kind === 'image') {
        res.writeHead(200, { 'content-type': preview.mediaType, 'cache-control': 'no-store' })
        res.end(bytes)
        return
      }
      return send(res, 200, preview)
    }
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
      const upstream = await fetch(`${upstreamOrigin}${url.pathname}${url.search}`)
      const html = await upstream.text()
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
      res.end(html.replace('</head>', `<style>${mobileStyle}</style><script>${mobileScript()}</script></head>`))
      return
    }
    await proxy(req, res, upstreamOrigin)
  }

  function publish(item) {
    if (listeners.size === 0) return Promise.reject(phoneError('no_phone'))
    const id = randomBytes(8).toString('hex')
    const deadline = performance.now() + pendingTimeoutMs
    let resolveResult, rejectResult
    const result = new Promise((resolve, reject) => { resolveResult = resolve; rejectResult = reject })
    let timer
    const record = {
      ...item, id,
      settle(value) {
        if (performance.now() >= deadline) {
          finish('phone_timeout')
          return false
        }
        return finish(undefined, value)
      },
      reject(error) { return finish(error.reason ?? 'no_phone') },
    }
    function finish(reason, value) {
      if (pending.get(id) !== record) return false
      pending.delete(id)
      clearTimeout(timer)
      const event = { kind: 'pending-settled', id, state: reason ?? 'answered' }
      for (const listener of [...listeners]) {
        try { listener(event) } catch { drop(listener) }
      }
      if (reason) rejectResult(phoneError(reason))
      else resolveResult(value)
      return true
    }
    pending.set(id, record)
    timer = setTimeout(() => finish('phone_timeout'), pendingTimeoutMs)
    for (const listener of [...listeners]) {
      if (!pending.has(id)) break
      try { listener(record) } catch { drop(listener) }
    }
    return result
  }

  function phoneError(reason) {
    return Object.assign(new Error(reason), { reason })
  }

  function pairing(origin) {
    return {
      ...store.issuePairing({ origin }),
      certificateSha256: tls ? certificateSha256(tls.cert) : undefined,
    }
  }

  function notify(item) {
    const record = { ...item, id: randomBytes(8).toString('hex') }
    for (const listener of listeners) listener(record)
  }

  function attachEvents(req, res, deviceId) {
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-store',
      connection: 'keep-alive',
    })
    // Without flushing, a phone that is waiting for its first event never sees headers.
    res.flushHeaders?.()
    const listener = record => res.write(`data: ${JSON.stringify(publicEvent(record))}\n\n`)
    listeners.add(listener)
    eventStreams.set(res, deviceId)
    for (const record of pending.values()) listener(record)
    req.on('close', () => { eventStreams.delete(res); drop(listener) })
  }

  function drop(listener) {
    listeners.delete(listener)
    if (listeners.size > 0) return
    for (const record of pending.values()) record.reject(Object.assign(new Error('no_phone'), { reason: 'no_phone' }))
    pending.clear()
  }

  function disconnectDevice(deviceId) {
    let closed = 0
    for (const [stream, owner] of eventStreams) {
      if (owner !== deviceId) continue
      stream.end()
      closed += 1
    }
    return closed
  }

  return {
    status,
    pairing,
    publish,
    disconnectDevice,
    notify,
    listenerCount: () => listeners.size,
    setRemoteOrigins(origins) {
      configuredOrigins = [...origins]
    },
    events(listener) {
      listeners.add(listener)
      return () => drop(listener)
    },
    async start() {
      server = tls ? createHttpsServer(tls, handler) : createHttpServer(handler)
      server.on('upgrade', (req, socket, head) => proxyUpgrade(req, socket, head, store, upstreamOrigin))
      await new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(port, '0.0.0.0', resolve)
      })
      listening = true
      try {
        beacon = createSocket({ type: 'udp4', reuseAddr: true })
        beacon.on('message', (message, remote) => {
          if (message.toString() !== 'dsh-app-discover') return
          const payload = Buffer.from(JSON.stringify({ name: 'dsh-app', port: boundPort(), origins: origins() }))
          beacon.send(payload, remote.port, remote.address)
        })
        await new Promise((resolve, reject) => {
          beacon.once('error', reject)
          beacon.bind(34430, resolve)
        })
      } catch {
        beacon?.close()
        beacon = undefined
      }
      return status()
    },
    async stop() {
      for (const record of [...pending.values()]) record.reject(phoneError('no_phone'))
      for (const stream of eventStreams.keys()) stream.end()
      eventStreams.clear()
      listeners.clear()
      if (beacon) beacon.close()
      if (!server) return
      // Keep-alive clients would otherwise hold the listener open forever.
      server.closeIdleConnections?.()
      await new Promise(resolve => {
        server.close(resolve)
        server.closeAllConnections?.()
      })
      listening = false
    },
  }
}

export function lanHosts() {
  const hosts = []
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.internal || entry.family !== 'IPv4') continue
      hosts.push(entry.address)
    }
  }
  return hosts
}

async function proxy(req, res, upstreamOrigin) {
  const upstream = await fetch(`${upstreamOrigin}${req.url}`, {
    method: req.method,
    headers: { ...req.headers, host: new URL(upstreamOrigin).host },
    duplex: 'half',
    body: req.method === 'GET' || req.method === 'HEAD' ? undefined : req,
  })
  res.writeHead(upstream.status, Object.fromEntries(upstream.headers))
  if (upstream.body) res.end(Buffer.from(await upstream.arrayBuffer()))
  else res.end()
}

function publicEvent(record) {
  const { settle, reject, ...event } = record
  return event
}

function proxyUpgrade(req, socket, head, store, upstreamOrigin) {
  const decision = store.access({
    authorization: req.headers.authorization,
    cookie: req.headers.cookie,
    origin: req.headers.origin,
    host: req.headers.host,
  })
  if (!decision.allow) {
    socket.end('HTTP/1.1 401 Unauthorized\r\nconnection: close\r\n\r\n')
    return
  }
  const upstream = new URL(upstreamOrigin)
  const request = (upstream.protocol === 'https:' ? httpsRequest : httpRequest)({
    protocol: upstream.protocol,
    hostname: upstream.hostname,
    port: upstream.port,
    path: req.url,
    method: req.method,
    headers: { ...req.headers, host: upstream.host },
  })
  request.on('upgrade', (response, upstreamSocket, upstreamHead) => {
    const headers = Object.entries(response.headers).map(([key, value]) => `${key}: ${value}`).join('\r\n')
    socket.write(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n${headers}\r\n\r\n`)
    if (upstreamHead.length) socket.write(upstreamHead)
    if (head.length) upstreamSocket.write(head)
    upstreamSocket.pipe(socket)
    socket.pipe(upstreamSocket)
  })
  request.on('error', () => socket.destroy())
  request.end()
}

function send(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}

const BODY_LIMIT = 64 * 1024

async function readJson(req) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > BODY_LIMIT) {
      // Stop before buffering more: the peer chooses how much it sends.
      throw Object.assign(new Error('payload_too_large'), { status: 413, code: 'payload_too_large' })
    }
    chunks.push(chunk)
  }
  if (chunks.length === 0) return {}
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    throw Object.assign(new Error('invalid_json'), { status: 400, code: 'invalid_json' })
  }
}
