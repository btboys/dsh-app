export const mobileStyle = `
@media (max-width: 840px) {
  :root { --dsh-app-pad: 12px; }
  body { overflow-x: hidden; }
  [class*="sidebar"], [data-slot="sidebar"] {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 20;
    max-width: min(86vw, 320px);
    transform: translateX(-105%);
  }
  body.dsh-app-nav [class*="sidebar"],
  body.dsh-app-nav [data-slot="sidebar"] { transform: none; }
}
`.trim()

export function mobileScript() {
  return `
(() => {
  const button = document.createElement('button')
  button.type = 'button'
  button.textContent = '会话'
  button.setAttribute('aria-label', '打开会话列表')
  button.style.cssText = 'position:fixed;left:12px;bottom:12px;z-index:30;min-width:44px;min-height:44px;border:0;border-radius:999px;background:#123;color:#fff;padding:0 14px'
  button.addEventListener('click', () => document.body.classList.toggle('dsh-app-nav'))
  document.addEventListener('click', (event) => {
    if (event.target === button) return
    if (event.target.closest?.('[class*="sidebar"], [data-slot="sidebar"]')) return
    document.body.classList.remove('dsh-app-nav')
  })
  document.body.append(button)
})()
`.trim()
}
