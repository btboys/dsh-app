export function diagnose({ upstreamOrigin, upstreamReachable, listening, origins, pairedDevices }) {
  const checks = [
    { name: 'listener', ok: listening === true, detail: listening ? 'gateway is listening' : 'gateway is not listening' },
    { name: 'origins', ok: Array.isArray(origins) && origins.length > 0, detail: `${origins?.length ?? 0} origin(s)` },
    { name: 'pairing', ok: Number.isInteger(pairedDevices) && pairedDevices >= 0, detail: `${pairedDevices ?? 0} device(s)` },
    { name: 'upstream', ok: upstreamReachable === true, detail: upstreamOrigin },
  ]
  const failing = checks.find(item => !item.ok)?.name
  return failing ? { ok: false, failing, checks } : { ok: true, checks }
}
