import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TeamRun } from '@/api'
import type { Message } from '@/types'

vi.mock('../MessageBubble.vue', () => ({
  default: defineComponent({
    props: ['message'],
    setup: props => () => h('div', { 'data-message-id': String(props.message.id) }, props.message.content),
  }),
}))
vi.mock('../CompressionSummary.vue', () => ({ default: defineComponent({ setup: () => () => h('div') }) }))
vi.mock('../TeamAnnouncePanel.vue', () => ({
  default: defineComponent({ props: ['message'], setup: props => () => h('div', { 'data-announce-id': props.message.id }) }),
}))
vi.mock('@/composables/chat/useStickToBottom', () => ({
  useStickToBottom: () => ({
    scrollRef: { value: null }, contentRef: { value: null }, isAtBottom: { value: true },
    escapedFromLock: { value: false }, scrollToBottom: vi.fn(), resetLock: vi.fn(),
  }),
}))

import MessageList from '../MessageList.vue'

const messages = {
  chat: { loadingOlder: 'Loading', loadOlderMessages: 'Load older', scrollToBottom: 'Bottom' },
  teamRuns: {
    status: { planning: 'Planning', running: 'Running', awaiting_review: 'Review', finalizing: 'Finalizing', completed: 'Completed', partial: 'Partial', failed: 'Failed', cancelled: 'Cancelled' },
    duration: { day: 'd', hour: 'h', minute: 'm', second: 's' }, progress: '{done}/{total}', tasks: 'Tasks',
    emptyTasks: 'No tasks', assignee: 'Assignee', dependencies: 'Dependencies', noDependencies: 'None', result: 'Result',
    noResult: 'No result', summary: 'Summary', noSummary: 'No summary', deliverables: 'Deliverables', noDeliverables: 'None',
    cancel: 'Cancel', expand: 'Expand', collapse: 'Collapse', openTask: 'Open task', objective: 'Objective',
    taskProgress: 'Progress', stopReason: 'Stop reason',
  },
}

const message = (id: string, role: Message['role'], content: string, metadata?: unknown): Message => ({
  id, conversationId: 'lead', role, content, contentParts: [], metadata: metadata as never,
})
const run = (): TeamRun => ({
  id: '10', teamId: '20', workspaceId: '30', leadAgentId: '40', leadConversationId: 'lead',
  originMessageId: '1', title: 'Launch research', objective: 'Collect evidence', status: 'running',
  finalSummary: null, stopReason: null, metadata: null, startedAt: null, completedAt: null,
  createTime: null, updateTime: null, progress: { total: 0, done: 0, failed: 0, inReview: 0, percent: 0 }, tasks: [],
})

const apps: Array<ReturnType<typeof createApp>> = []
function mount(props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(MessageList, props)
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
  app.mount(host)
  apps.push(app)
  return host
}

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})

describe('MessageList team run timeline', () => {
  it('preserves legacy rendering when teamRuns is not provided', () => {
    const host = mount({ messages: [
      message('1', 'user', 'hello'),
      message('2', 'user', '[System Message] settled'),
    ] })

    expect(host.querySelectorAll('[data-message-id]')).toHaveLength(1)
    expect(host.querySelector('[data-announce-id="2"]')).not.toBeNull()
    expect(host.querySelector('[data-team-run-toggle]')).toBeNull()
  })

  it('renders an anchored run, hides linked bookkeeping, and expands a deep link', async () => {
    const host = mount({
      messages: [
        message('1', 'user', 'delegate'),
        message('2', 'user', 'protocol', { type: 'team_announce', runId: '10', taskId: '501' }),
        message('3', 'assistant', 'unrelated'),
      ],
      teamRuns: [run()],
      expandedTeamRunId: '10',
    })
    await nextTick()

    expect(Array.from(host.querySelectorAll('[data-message-id]')).map(node => node.getAttribute('data-message-id'))).toEqual(['1', '3'])
    expect(host.querySelector('[data-team-run-toggle]')?.getAttribute('aria-expanded')).toBe('true')
    expect(host.textContent).toContain('Launch research')
  })
})
