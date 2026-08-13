import { createApp, nextTick, type Component } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it } from 'vitest'
import type { TeamRun } from '@/api'
import TeamRunDrawer from '../TeamRunDrawer.vue'
import TeamRunsPanel from '../TeamRunsPanel.vue'

const messages = { teamRuns: {
  history: 'Run history', refresh: 'Refresh', loading: 'Loading runs', empty: 'No runs yet',
  loadError: 'Could not load runs', retryLoad: 'Retry', close: 'Close', partialNotice: 'Some tasks did not complete.',
  status: { planning: 'Planning', running: 'Running', awaiting_review: 'Awaiting review', finalizing: 'Finalizing', completed: 'Completed', partial: 'Partial', failed: 'Failed', cancelled: 'Cancelled' },
  duration: { day: 'd', hour: 'h', minute: 'm', second: 's' }, progress: '{done} of {total} complete',
  tasks: 'Tasks', emptyTasks: 'No tasks', assignee: 'Assignee', dependencies: 'Dependencies', noDependencies: 'None',
  result: 'Result', noResult: 'No result', summary: 'Summary', noSummary: 'No summary', deliverables: 'Deliverables',
  noDeliverables: 'No deliverables', cancel: 'Cancel run', objective: 'Objective', taskProgress: 'Task progress',
  stopReason: 'Stop reason', openTask: 'Open task',
} }

function run(extra: Partial<TeamRun> = {}): TeamRun {
  return { id: '20', teamId: '10', workspaceId: '1', leadAgentId: '2', leadConversationId: 'lead', originMessageId: null,
    title: 'Research', objective: 'Collect evidence', status: 'running', finalSummary: null, stopReason: null,
    metadata: null, startedAt: '2026-08-13T10:00:00Z', completedAt: null, createTime: '2026-08-13T10:00:00Z',
    updateTime: null, progress: { total: 1, done: 0, failed: 0, inReview: 0, percent: 30 }, tasks: [], ...extra }
}

const apps: Array<ReturnType<typeof createApp>> = []
function mount(component: Component, props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(component, props)
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
  app.mount(host)
  apps.push(app)
  return host
}
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = '' })

describe('TeamRunsPanel', () => {
  it('renders compact run rows and emits selection without flattening tasks', async () => {
    let selected = ''
    const host = mount(TeamRunsPanel, { runs: [run()], onSelectRun: (value: TeamRun) => { selected = value.id } })
    expect(host.textContent).toContain('Research')
    expect(host.querySelectorAll('[data-team-run-row]')).toHaveLength(1)
    expect(host.querySelector('[data-team-run-task-list]')).toBeNull()
    host.querySelector<HTMLButtonElement>('[data-team-run-row]')!.click()
    await nextTick()
    expect(selected).toBe('20')
  })

  it('renders loading, empty, and error states', () => {
    expect(mount(TeamRunsPanel, { runs: [], loading: true }).textContent).toContain('Loading runs')
    expect(mount(TeamRunsPanel, { runs: [] }).textContent).toContain('No runs yet')
    expect(mount(TeamRunsPanel, { runs: [], error: 'offline' }).textContent).toContain('Could not load runs')
  })
})

describe('TeamRunDrawer', () => {
  it('shows partial state and forwards close and cancel actions', async () => {
    let closed = 0
    let cancelled = ''
    const partialHost = mount(TeamRunDrawer, { run: run({ status: 'partial' }), open: true })
    expect(partialHost.textContent).toContain('Some tasks did not complete.')
    const host = mount(TeamRunDrawer, {
      run: run(), open: true, canCancel: true,
      onClose: () => { closed += 1 }, onCancel: (id: string) => { cancelled = id },
    })
    host.querySelector<HTMLButtonElement>('[data-team-run-cancel]')!.click()
    host.querySelector<HTMLButtonElement>('[data-team-run-drawer-close]')!.click()
    await nextTick()
    expect(cancelled).toBe('20')
    expect(closed).toBe(1)
  })
})
