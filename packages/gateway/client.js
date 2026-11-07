window.__ModuleLoader__.load({
  id: 'dsh-app-mobile',
  factory(require) {
    const React = require('react')
    const h = React.createElement

    // The module system claims a <style> appended while the factory materializes
    // and removes it on unload. A stylesheet also avoids the inline-style diff
    // that drops a longhand set against a shorthand and blanks the border.
    if (typeof document !== 'undefined') {
      const style = document.createElement('style')
      style.textContent = CSS
      document.head.append(style)
    }

    function MobileAccess() {
      const [status, setStatus] = React.useState(null)
      const [pairing, setPairing] = React.useState(null)
      const [remote, setRemote] = React.useState('')
      const [error, setError] = React.useState('')
      const [busy, setBusy] = React.useState('')
      const [copied, setCopied] = React.useState(false)

      function refresh() {
        return fetch('/dsh-app/desktop/status').then(response => response.json()).then(setStatus)
      }

      React.useEffect(() => {
        refresh().catch(cause => setError(String(cause)))
      }, [])

      function issue(origin) {
        setError('')
        setCopied(false)
        setBusy(origin)
        fetch('/dsh-app/desktop/pairing', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ origin }),
        })
          .then(response => response.json().then(body => ({ ok: response.ok, body })))
          .then(({ ok, body }) => {
            if (!ok) setError(body.error ?? '无法生成配对码')
            else setPairing(body)
          })
          .catch(cause => setError(String(cause)))
          .finally(() => setBusy(''))
      }

      const origins = status?.origins ?? []
      const on = status?.listening === true

      return h('section', { className: 'dam' },
        h('header', { className: 'dam-head' },
          h('div', null,
            h('p', { className: 'dam-kicker' }, '远程控制'),
            h('h2', { className: 'dam-title' }, '移动访问'),
            h('p', { className: 'dam-desc' }, '手机和这台电脑使用同一份会话。点一个地址生成配对码；离开局域网时，用你自己的 Tailscale 或反向代理地址。')),
          status ? h('span', { className: 'dam-status' },
            h('span', { className: on ? 'dam-dot' : 'dam-dot dam-off' }),
            on ? `网关运行中 · ${status.port}` : '网关未启动') : null),
        error ? h('p', { role: 'alert', className: 'dam-alert' }, error) : null,
        h('div', { className: 'dam-section' },
          h('h3', { className: 'dam-label' }, '局域网'),
          origins.length
            ? origins.map(origin => h('div', { key: origin, className: 'dam-card' },
              h('span', { className: 'dam-chip' }, 'LAN'),
              h('span', { className: 'dam-addr' }, origin),
              h('button', { type: 'button', className: 'dam-btn', disabled: busy === origin, onClick: () => issue(origin) }, '生成配对码')))
            : h('p', { className: 'dam-empty' }, '还没有可用的局域网地址。')),
        h('div', { className: 'dam-section' },
          h('h3', { className: 'dam-label' }, '远程地址'),
          h('form', {
            className: 'dam-row',
            onSubmit: event => {
              event.preventDefault()
              if (remote.trim()) issue(remote.trim())
            },
          },
          h('input', {
            className: 'dam-input',
            value: remote,
            placeholder: 'https://your-host.example',
            spellCheck: false,
            onChange: event => setRemote(event.target.value),
          }),
          h('button', { type: 'submit', className: 'dam-btn dam-primary', disabled: !remote.trim() }, '生成配对码'))),
        pairing ? h('div', { className: 'dam-code' },
          h('span', { className: 'dam-addr dam-muted' }, pairing.origin),
          h('div', { className: 'dam-code-row' },
            h('code', { className: 'dam-code-value' }, pairing.code),
            h('button', {
              type: 'button',
              className: 'dam-btn',
              onClick: () => navigator.clipboard.writeText(pairing.code).then(() => setCopied(true)).catch(() => setCopied(false)),
            }, copied ? '已复制' : '复制')),
          h('p', { className: 'dam-empty' }, '五分钟内在 App 中粘贴一次。配对码不会写入设备记录。')) : null,
        h('div', { className: 'dam-section' },
          h('h3', { className: 'dam-label' }, '已配对设备'),
          (status?.devices ?? []).length
            ? status.devices.map(device => h('div', { key: device.id, className: 'dam-card' },
              h('span', { className: device.revoked ? 'dam-dot dam-off' : 'dam-dot' }),
              h('span', { className: 'dam-device' },
                h('strong', null, device.name),
                h('span', { className: 'dam-sub' }, device.revoked ? `${device.origin} · 已撤销` : device.origin)),
              device.revoked ? null : h('button', {
                type: 'button',
                className: 'dam-btn dam-danger',
                onClick: () => fetch('/dsh-app/desktop/revoke', {
                  method: 'POST',
                  headers: { 'content-type': 'application/json' },
                  body: JSON.stringify({ id: device.id }),
                }).then(refresh),
              }, '撤销')))
            : h('p', { className: 'dam-empty' }, '还没有配对的设备。')))
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.slots.inject('settings.section', () => ctx.slots.register({
          name: 'settings.section',
          id: 'mobile-access',
          order: 40,
          label: () => '移动访问',
        }, MobileAccess))
      },
    }
  },
})

const CSS = [
  '.dam{display:grid;gap:22px;max-width:640px;color:var(--dsw-alias-label-primary,inherit)}',
  '.dam-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}',
  '.dam-kicker{margin:0 0 4px;font-size:11px;letter-spacing:.16em;text-transform:uppercase;color:var(--dsw-alias-brand-primary,currentColor)}',
  '.dam-title{margin:0;font-size:18px;font-weight:640}',
  '.dam-desc{margin:6px 0 0;max-width:54ch;line-height:1.55;font-size:13.5px;color:var(--dsw-alias-label-secondary,inherit)}',
  '.dam-status{display:inline-flex;align-items:center;gap:7px;flex:none;margin-top:2px;padding:4px 10px;border:1px solid var(--dsw-alias-border-l1,transparent);border-radius:999px;background:var(--dsw-alias-bg-layer-1,transparent);font-size:12px;white-space:nowrap}',
  '.dam-dot{width:7px;height:7px;border-radius:50%;flex:none;background:var(--dsw-alias-state-success-primary,currentColor)}',
  '.dam-off{background:var(--dsw-alias-state-warn-primary,currentColor)}',
  '.dam-section{display:grid;gap:8px}',
  '.dam-label{margin:0;font-size:12px;font-weight:600;letter-spacing:.04em;color:var(--dsw-alias-label-secondary,inherit)}',
  '.dam-card{display:flex;align-items:center;gap:12px;padding:9px 12px;border:1px solid var(--dsw-alias-border-l1,transparent);border-radius:10px;background:var(--dsw-alias-bg-layer-1,transparent)}',
  '.dam-chip{flex:none;padding:2px 7px;border:1px solid var(--dsw-alias-border-l1,transparent);border-radius:999px;font-size:10px;letter-spacing:.08em;color:var(--dsw-alias-label-secondary,inherit)}',
  '.dam-addr{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}',
  '.dam-muted{color:var(--dsw-alias-label-secondary,inherit);font-size:12px}',
  '.dam-device{display:grid;gap:2px;flex:1;min-width:0}',
  '.dam-device strong{font-size:13.5px;font-weight:600}',
  '.dam-sub{font-size:12px;color:var(--dsw-alias-label-secondary,inherit)}',
  '.dam-row{display:flex;gap:8px}',
  '.dam-input{flex:1;min-width:0;min-height:36px;padding:0 12px;border:1px solid var(--dsw-alias-border-l2,transparent);border-radius:8px;background:var(--dsw-alias-bg-base,transparent);color:inherit;font:inherit;font-size:13.5px}',
  '.dam-btn{flex:none;min-height:34px;padding:0 12px;border:1px solid var(--dsw-alias-border-l2,transparent);border-radius:8px;background:var(--dsw-alias-bg-layer-2,transparent);color:var(--dsw-alias-label-primary,inherit);font:inherit;font-size:13px;cursor:pointer}',
  '.dam-primary{border-color:transparent;background:var(--dsw-alias-brand-primary,currentColor);color:var(--dsw-alias-bg-base,#fff);font-weight:600}',
  '.dam-danger{color:var(--dsw-alias-state-error-primary,currentColor)}',
  '.dam-code{display:grid;gap:8px;padding:14px 16px;border:1px solid var(--dsw-alias-border-l1,transparent);border-left:3px solid var(--dsw-alias-brand-primary,currentColor);border-radius:10px;background:var(--dsw-alias-bg-layer-1,transparent)}',
  '.dam-code-row{display:flex;align-items:center;justify-content:space-between;gap:12px}',
  '.dam-code-value{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:22px;letter-spacing:.03em;word-break:break-all}',
  '.dam-empty{margin:0;font-size:12.5px;color:var(--dsw-alias-label-secondary,inherit)}',
  '.dam-alert{margin:0;padding:8px 12px;border:1px solid var(--dsw-alias-state-error-primary,currentColor);border-radius:8px;color:var(--dsw-alias-state-error-primary,currentColor);font-size:13px}',
].join('')
