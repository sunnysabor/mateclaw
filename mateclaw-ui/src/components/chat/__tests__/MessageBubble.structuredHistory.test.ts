import { createApp, defineComponent, h } from 'vue'
import { createI18n } from 'vue-i18n'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '@/types'

vi.mock('@/composables/useStreamingMarkdown', () => ({
  useStreamingMarkdown: (content: unknown) => ({
    renderedContent: content,
    copyCode: vi.fn(),
  }),
}))
vi.mock('@/composables/useAuthenticatedAttachment', () => ({
  useAuthenticatedAttachment: () => ({
    blobUrls: {},
    loadAllImages: vi.fn(),
    loadAllVideos: vi.fn(),
    loadAllAudios: vi.fn(),
    loadAllModels: vi.fn(),
    downloadFile: vi.fn(),
    openImage: vi.fn(),
    getDisplayUrl: vi.fn((url: string) => url),
    revokeAll: vi.fn(),
  }),
}))
vi.mock('@/composables/useMcToast', () => ({
  mcToast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))
vi.mock('@/composables/useToolLabel', () => ({
  useToolLabel: () => ({ getToolLabel: (name: string) => name }),
}))
vi.mock('@/api', () => ({ http: { get: vi.fn(), post: vi.fn() }, fetchAuthenticatedBlob: vi.fn() }))
vi.mock('@/utils/clipboard', () => ({ copyToClipboard: vi.fn() }))
vi.mock('@/utils/generatedFileLinks', () => ({
  buildGeneratedFileNameMap: vi.fn(() => new Map()),
  linkifyGeneratedFileUrls: vi.fn((content: string) => content),
}))
vi.mock('@/utils/lazyModelViewer', () => ({ ensureModelViewer: vi.fn() }))
vi.mock('../preview/previewKind', () => ({ previewKindOf: vi.fn(() => null) }))
vi.mock('../preview/previewBus', () => ({ openFilePreview: vi.fn() }))
vi.mock('@/components/goal/GoalAvatarRing.vue', () => ({
  default: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }),
}))
vi.mock('../ThinkingSegment.vue', () => ({
  default: defineComponent({ props: ['segment'], setup: props => () => h('div', props.segment.thinkingText) }),
}))
vi.mock('../ContentSegment.vue', () => ({
  default: defineComponent({ props: ['segment'], setup: props => () => h('div', props.segment.text) }),
}))
vi.mock('../PlanStepsPanel.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../BrowserTimeline.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../TypingCursor.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../UserMessageContent.vue', () => ({
  default: defineComponent({ props: ['content'], setup: props => () => h('div', props.content) }),
}))

import MessageBubble from '../MessageBubble.vue'
import { http, fetchAuthenticatedBlob } from '@/api'
import { mcToast } from '@/composables/useMcToast'

const apps: Array<ReturnType<typeof createApp>> = []

function mountMessage(message: Message, locale = 'zh-CN') {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(MessageBubble, { message })
  app.use(createPinia())
  for (const name of ['el-icon', 'el-popover', 'model-viewer']) app.component(name, { template: '<span><slot /></span>' })
  app.use(createI18n({
    legacy: false,
    missingWarn: false,
    fallbackWarn: false,
    locale,
    messages: {
      'zh-CN': {
        chat: {
          stopped: '已被用户手动中止',
          interrupted: '已中断并继续处理下一条消息',
        },
      },
      en: {
        chat: {
          stopped: 'Stopped manually by user',
          interrupted: 'Interrupted and continued with the next message',
        },
      },
    },
  }))
  app.mount(host)
  apps.push(app)
  return host
}

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('structured tool history', () => {
  it.each(['segments', 'toolCalls'])('renders saved %s data as the same visible table', (source) => {
    const structuredContent = { mateclawUi: { version: 1, blocks: [{ type: 'table', data: { columns: [{ key: 'a', label: 'A' }], rows: [{ a: 'Saved result' }] } }] } }
    const metadata = source === 'segments'
      ? { segments: [{ id: 'tool', type: 'tool_call', status: 'completed', toolName: 'search', structuredContent }] }
      : { toolCalls: [{ name: 'search', status: 'completed', success: true, structuredContent }] }
    const host = mountMessage({ id: 'saved', conversationId: 'conv', role: 'assistant', content: '', contentParts: [], status: 'completed', metadata } as Message)
    expect(host.querySelector('td')?.textContent).toBe('Saved result')
    expect(host.querySelector('.seg-tool__body')).toBeNull()
  })
})
