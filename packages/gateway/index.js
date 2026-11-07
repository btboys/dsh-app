import { Buffer } from 'node:buffer'
import { realpathSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { createGateway } from './gateway.js'
import { DeviceStore } from './store.js'
import { loadOrCreateCertificate } from './tls.js'

const HOME = join(homedir(), '.dsh-app')
const schema = hostRequire()('@deepseek-ai/schemastery')
const z = schema.z ?? schema

export const inject = ['webApp', 'webServer', 'workspaceRegistry']

export const Config = z.object({
  port: z.number().default(3443),
  remoteOrigins: z.array(z.string()).default([]),
  tls: z.boolean().default(true),
})

export async function apply(ctx, config = {}) {
  const store = new DeviceStore(join(HOME, 'devices'))
  await store.load()
  const tls = config.tls === false ? undefined : await loadOrCreateCertificate(join(HOME, 'tls'))
  const gateway = createGateway({
    store,
    tls,
    upstreamOrigin: ctx.webApp.getAdvertisedUrl(),
    port: config.port ?? 3443,
    remoteOrigins: config.remoteOrigins ?? [],
    workspaces: () => ctx.workspaceRegistry.list().map(workspace => ({
      id: workspace.id,
      title: workspace.title,
      path: workspace.path,
      async list(target) {
        const { readdir, stat } = await import('node:fs/promises')
        const entries = await readdir(target, { withFileTypes: true })
        return {
          path: target,
          entries: await Promise.all(entries.map(async entry => ({
            name: entry.name,
            type: entry.isDirectory() ? 'directory' : entry.isFile() ? 'file' : 'other',
            size: entry.isFile() ? (await stat(join(target, entry.name))).size : undefined,
          }))),
        }
      },
      async read(target) {
        return readFile(target)
      },
    })),
  })
  const disposeRoutes = [
    ctx.webServer.register({ kind: 'exact', path: '/dsh-app/desktop/status', handler: desktop((req, res) => {
      json(res, 200, gateway.status())
    }) }),
    ctx.webServer.register({ kind: 'exact', path: '/dsh-app/desktop/pairing', handler: desktop(async (req, res) => {
      if (req.method !== 'POST') return json(res, 405, { error: 'method_not_allowed' })
      const body = await readBody(req)
      json(res, 200, gateway.pairing(body.origin))
    }) }),
    ctx.webServer.register({ kind: 'exact', path: '/dsh-app/desktop/revoke', handler: desktop(async (req, res) => {
      if (req.method !== 'POST') return json(res, 405, { error: 'method_not_allowed' })
      const body = await readBody(req)
      const revoked = await store.revoke(body.id)
      // A revoked phone must lose the live stream it is already holding, not just new requests.
      if (revoked) gateway.disconnectDevice(body.id)
      json(res, 200, { revoked })
    }) }),
  ]
  ctx.on('approval/request', async (request, next) => claimOrDelegate(gateway, next, {
    kind: 'approval',
    toolName: request.toolName,
    reason: request.displayReason?.zh ?? request.displayReason?.en ?? request.reason ?? request.toolName,
    sessionId: request.agent?.id,
  }))
  ctx.on('user-questions/request', async (request, next) => claimOrDelegate(gateway, next, {
    kind: 'question',
    questions: request.questions,
    sessionId: request.agent?.id,
  }))
  ctx.on('agent/status', payload => {
    gateway.notify({ kind: 'status', sessionId: payload.agent.id, status: payload.status })
  })
  await gateway.start()
  return () => {
    for (const dispose of disposeRoutes) dispose()
    return gateway.stop()
  }
}

async function claimOrDelegate(gateway, next, item) {
  try {
    return await gateway.publish(item)
  } catch (error) {
    if (error.reason === 'no_phone' || error.reason === 'phone_timeout') return next()
    throw error
  }
}

function desktop(handler) {
  return (req, res) => {
    const address = req.socket?.remoteAddress
    if (address !== '127.0.0.1' && address !== '::1' && address !== '::ffff:127.0.0.1') {
      json(res, 403, { error: 'loopback_required' })
      return
    }
    Promise.resolve(handler(req, res)).catch(error => json(res, 400, { error: error.message }))
  }
}

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}

async function readBody(req) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}
}

function hostRequire() {
  const local = createRequire(import.meta.url)
  try {
    local.resolve('@deepseek-ai/schemastery')
    return local
  } catch {
    if (!process.argv[1]) throw new Error('找不到 DSH 安装目录')
    return createRequire(realpathSync(process.argv[1]))
  }
}
