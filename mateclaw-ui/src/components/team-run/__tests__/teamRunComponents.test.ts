import { createApp, nextTick, type Component } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it } from 'vitest'
import type { TeamRun } from '@/api'
import TeamRunCard from '../TeamRunCard.vue'
import TeamRunDetail from '../TeamRunDetail.vue'

const messages = {
  teamRuns: {
    status: {
      planning: 'Planning', running: 'Running', awaiting_review: 'Awaiting review', finalizing: 'Finalizing',
      completed: 'Completed', partial: 'Partial', failed: 'Failed', cancelled: 'Cancelled',
    },
    duration: { day: 'd', hour: 'h', minute: 'm', second: 's' },
    progress: '{done} of {total} complete',
    tasks: 'Tasks', emptyTasks: 'No tasks in this run', assignee: 'Assignee', dependencies: 'Dependencies',
    noDependencies: 'None', result: 'Result', noResult: 'No result yet', summary: 'Summary',
    noSummary: 'No summary yet', deliverables: 'Deliverables', noDeliverables: 'No deliverables',
    cancel: 'Cancel run', expand: 'Expand run', collapse: 'Collapse run', openTask: 'Open task',
    objective: 'Objective', taskProgress: 'Task progress', stopReason: 'Stop reason',
  },
}

function sampleRun(extra: Partial<TeamRun> = {}): TeamRun {
  return {
    id: '20', teamId: '10', workspaceId: '1', leadAgentId: '30', leadConversationId: 'lead-1',
    originMessageId: null, title: 'Research launch', objective: 'Prepare launch research', status: 'running',
    finalSummary: null, stopReason: null, metadata: null, startedAt: '2026-08-13T10:00:00Z', completedAt: null,
    createTime: null, updateTime: null, progress: { total: 0, done: 0, failed: 0, inReview: 0, percent: 0 },
    tasks: [], ...extra,
  }
}

const mounted: Array<ReturnType<typeof createApp>> = []

function mount(component: Component, props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(component, props)
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
  app.mount(host)
  mounted.push(app)
  return host
}

afterEach(() => {
  mounted.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})

describe('TeamRunCard', () => {
  it.each([
    ['planning', 'Planning'],
    ['running', 'Running'],
    ['awaiting_review', 'Awaiting review'],
    ['partial', 'Partial'],
    ['failed', 'Failed'],
    ['cancelled', 'Cancelled'],
  ] as const)('renders the %s run state', (status, label) => {
    const host = mount(TeamRunCard, { run: sampleRun({ status }) })

    expect(host.textContent).toContain(label)
  })

  it('uses focused Enter and Space activation without click fallback', async () => {
    const toggles: boolean[] = []
    const host = mount(TeamRunCard, { run: sampleRun(), onToggle: (value: boolean) => toggles.push(value) })
    const toggle = host.querySelector<HTMLButtonElement>('[data-team-run-toggle]')!

    expect(toggle.tagName).toBe('BUTTON')
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    toggle.focus()
    expect(document.activeElement).toBe(toggle)
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    toggle.dispatchEvent(enter)
    await nextTick()

    expect(enter.defaultPrevented).toBe(true)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(host.textContent).toContain('No tasks in this run')
    expect(toggles).toEqual([true])

    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    toggle.dispatchEvent(space)
    await nextTick()
    expect(space.defaultPrevented).toBe(true)
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(toggles).toEqual([true, false])
  })
})

describe('TeamRunDetail', () => {
  it('renders summary, task drilldown and emits cancel', async () => {
    let cancelled = 0
    const task = {
      id: '101', teamId: '10', runId: '20', taskNumber: 1, subject: 'Collect evidence', description: 'Read sources',
      status: 'completed', priority: 0, taskType: 'execution', assigneeAgentId: '31', ownerAgentId: null,
      blockedBy: null, requireApproval: false, progressPercent: 100, progressStep: null,
      result: 'Evidence collected', reason: null, conversationId: 'worker-1', metadata: null,
      createTime: null, updateTime: null,
    }
    const host = mount(TeamRunDetail, {
      run: sampleRun({ finalSummary: 'Launch is viable', progress: { total: 1, done: 1, failed: 0, inReview: 0, percent: 100 }, tasks: [task] }),
      canCancel: true,
      selectedTaskId: '101',
      onCancel: () => { cancelled += 1 },
    })

    expect(host.textContent).toContain('Launch is viable')
    expect(host.textContent).toContain('Evidence collected')
    host.querySelector<HTMLButtonElement>('[data-team-run-cancel]')!.click()
    await nextTick()
    expect(cancelled).toBe(1)
  })

  it('renders markdown in the run summary instead of one flattened paragraph', async () => {
    const host = mount(TeamRunDetail, {
      run: sampleRun({
        status: 'completed',
        finalSummary: '## 结论\n\n| 项目 | 状态 |\n| --- | --- |\n| 任务 | **完成** |',
      }),
    })
    await nextTick()

    expect(host.querySelector('.run-detail__markdown h2')?.textContent).toBe('结论')
    expect(host.querySelector('.run-detail__markdown table')).not.toBeNull()
    expect(host.querySelector('.run-detail__markdown strong')?.textContent).toBe('完成')
  })
})
