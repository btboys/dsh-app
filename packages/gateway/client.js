window.__ModuleLoader__.load({
  id: '@local/dsh-app',
  factory(require) {
    const React = require('react')
    const h = React.createElement

    function MobileAccess() {
      const [status, setStatus] = React.useState(null)
      const [pairing, setPairing] = React.useState(null)
      const [remote, setRemote] = React.useState('')
      const [error, setError] = React.useState('')

      async function refresh() {
        const response = await fetch('/dsh-app/desktop/status')
        setStatus(await response.json())
      }

      React.useEffect(() => {
        refresh().catch(cause => setError(String(cause)))
      }, [])

      async function issue(origin) {
        setError('')
        const response = await fetch('/dsh-app/desktop/pairing', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ origin }),
        })
        const body = await response.json()
        if (!response.ok) {
          setError(body.error ?? '无法生成配对码')
          return
        }
        setPairing(body)
      }

      return h('section', {
        style: {
          display: 'grid',
          gap: 16,
          padding: 20,
          color: 'var(--dsw-alias-fg-1, inherit)',
          maxWidth: 720,
        },
      },
      h('h2', { style: { margin: 0, fontSize: 20 } }, '移动访问'),
      h('p', { style: { margin: 0, lineHeight: 1.5 } }, '手机和这台电脑使用同一份会话。先在可信网络配对；离开局域网时，把下面的地址交给你自己的 Tailscale 或反向代理。'),
      error ? h('p', { role: 'alert' }, error) : null,
      h('h3', { style: { margin: 0, fontSize: 16 } }, '局域网'),
      ...(status?.origins ?? []).filter(origin => !status.remoteOrigins?.includes(origin)).map(origin => h('button', {
        key: origin,
        type: 'button',
        onClick: () => issue(origin),
        style: buttonStyle,
      }, origin)),
      h('h3', { style: { margin: 0, fontSize: 16 } }, '远程地址'),
      h('form', {
        onSubmit: event => {
          event.preventDefault()
          issue(remote)
        },
        style: { display: 'flex', gap: 8 },
      },
      h('input', {
        value: remote,
        placeholder: 'https://your-host.example',
        onChange: event => setRemote(event.target.value),
        style: { flex: 1, minHeight: 40, padding: '0 12px' },
      }),
      h('button', { type: 'submit', style: buttonStyle }, '生成远程配对码')),
      pairing ? h('div', { style: { display: 'grid', gap: 8 } },
        h('strong', null, pairing.origin),
        h('code', { style: { fontSize: 18, wordBreak: 'break-all' } }, pairing.code),
        pairing.certificateSha256 ? h('div', { style: { display: 'grid', gap: 4 } },
          h('span', { style: { fontSize: 12, opacity: 0.7 } }, '证书指纹（在手机上核对后再配对）'),
          h('code', { style: { fontSize: 12, wordBreak: 'break-all' } }, pairing.certificateSha256)) : null,
        h('p', { style: { margin: 0 } }, pairing.certificateSha256
          ? '五分钟内把配对码和上面的证书指纹一起粘贴到 App。指纹不一致说明连接被拦截，请停止配对。'
          : '五分钟内在 App 中粘贴一次。配对码不会写入设备记录。')) : null,
      h('h3', { style: { margin: 0, fontSize: 16 } }, '已配对设备'),
      ...(status?.devices ?? []).map(device => h('div', {
        key: device.id,
        style: { display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' },
      },
      h('span', null, `${device.name} · ${device.origin}${device.revoked ? ' · 已撤销' : ''}`),
      device.revoked ? null : h('button', {
        type: 'button',
        style: buttonStyle,
        onClick: async () => {
          await fetch('/dsh-app/desktop/revoke', {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ id: device.id }),
          })
          await refresh()
        },
      }, '撤销'))))
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

const buttonStyle = {
  minHeight: 40,
  padding: '0 14px',
  borderRadius: 8,
  border: '1px solid var(--dsw-alias-border-1, transparent)',
  background: 'var(--dsw-alias-bg-2, transparent)',
  color: 'inherit',
}
