import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import DshSettings from '../index.vue'

const api = vi.hoisted(() => ({ status: vi.fn(), testConnection: vi.fn() }))
vi.mock('@/api', () => ({ dshApi: api }))
vi.mock('@/composables/useMcToast', () => ({ mcToast: { success: vi.fn(), error: vi.fn() } }))
let unmount: (() => void) | undefined
afterEach(() => { unmount?.(); document.body.innerHTML = ''; sessionStorage.clear(); vi.clearAllMocks() })
async function flush() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }

it('clears a previous successful handshake after a failed recheck', async () => {
  const ready = { state: 'READY', installed: true, enabled: false, config: {}, managed: {}, handshake: { success: true } }
  api.status.mockResolvedValueOnce({ data: ready }).mockResolvedValue({ data: { ...ready, handshake: { success: false } } })
  api.testConnection.mockResolvedValue({ data: { success: false, message: 'SDK handshake failed' } })
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(DshSettings); app.mount(host); unmount = () => app.unmount()
  await flush()
  const button = (label: string) => [...host.querySelectorAll('button')].find(item => item.textContent === label)!
  expect(button('启用 DSH').disabled).toBe(false)
  button('测试 SDK 握手').click(); await flush()
  expect(button('启用 DSH').disabled).toBe(true)
  expect(host.querySelector('.error-card')?.textContent).toContain('SDK handshake failed')
  expect(api.status).toHaveBeenCalledTimes(2)
})
