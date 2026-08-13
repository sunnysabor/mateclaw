import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { TeamRun } from '@/api'
import {
  buildTeamsRouteQuery,
  clearTeamsRunSelection,
  parseTeamsRouteQuery,
  reconcileTeamsRoute,
} from '../teamsRouteState'
import { sortTeamRuns, useTeamRunHistory } from '../useTeamRunHistory'

function run(id: string, createTime: string | null, status: TeamRun['status'] = 'running'): TeamRun {
  return {
    id, teamId: '10', workspaceId: '1', leadAgentId: '2', leadConversationId: 'lead', originMessageId: null,
    title: `Run ${id}`, objective: 'Objective', status, finalSummary: null, stopReason: null, metadata: null,
    startedAt: createTime, completedAt: null, createTime, updateTime: createTime,
    progress: { total: 0, done: 0, failed: 0, inReview: 0, percent: 0 }, tasks: [],
  }
}

describe('teams run routes', () => {
  it('hydrates string ids and defaults an opened team to runs', () => {
    expect(parseTeamsRouteQuery({ teamId: '10', runId: '20', taskId: '30' })).toEqual({
      teamId: '10', view: 'runs', runId: '20', taskId: '30',
    })
    expect(parseTeamsRouteQuery({ teamId: ['10'], view: 'unknown', runId: 20 })).toEqual({
      teamId: '10', view: 'runs', runId: null, taskId: null,
    })
    expect(parseTeamsRouteQuery({})).toEqual({ teamId: null, view: null, runId: null, taskId: null })
  })

  it('reconciles browser navigation from task A to B to no task without navigation writes', () => {
    const base = parseTeamsRouteQuery({ teamId: '10', view: 'runs', runId: '20', taskId: 'A' })
    const taskB = parseTeamsRouteQuery({ teamId: '10', view: 'runs', runId: '20', taskId: 'B' })
    const noTask = parseTeamsRouteQuery({ teamId: '10', view: 'runs', runId: '20' })
    const board = parseTeamsRouteQuery({ teamId: '10', view: 'board', runId: '20', taskId: 'B' })

    expect(reconcileTeamsRoute(base, taskB)).toMatchObject({
      selectedRunId: '20', selectedTaskId: 'B', taskAction: 'load',
    })
    expect(reconcileTeamsRoute(taskB, noTask)).toMatchObject({
      selectedRunId: '20', selectedTaskId: null, taskAction: 'close',
    })
    expect(reconcileTeamsRoute(taskB, board)).toMatchObject({
      selectedRunId: null, selectedTaskId: null, taskAction: 'close',
    })
  })

  it('builds stable route queries without coercing snowflake ids', () => {
    expect(buildTeamsRouteQuery('9007199254740993', 'runs', '9007199254740995', '9007199254740997'))
      .toEqual({ teamId: '9007199254740993', view: 'runs', runId: '9007199254740995', taskId: '9007199254740997' })
    expect(buildTeamsRouteQuery('10', 'members')).toEqual({ teamId: '10', view: 'members' })
    expect(clearTeamsRunSelection(parseTeamsRouteQuery({
      teamId: '10', view: 'runs', runId: '9007199254740995', taskId: '9007199254740997',
    }))).toEqual({ teamId: '10', view: 'runs' })
  })
})

describe('useTeamRunHistory', () => {
  it('keeps an SSE detail overlay when the initial list resolves later', async () => {
    let resolveList!: (value: unknown) => void
    let callback: ((event: { event: string; data: Record<string, unknown> }) => void) | undefined
    const listByTeam = vi.fn().mockReturnValue(new Promise(resolve => { resolveList = resolve }))
    const get = vi.fn().mockResolvedValue({ data: run('1', '2026-03-01', 'completed') })
    const timers: Array<() => void> = []
    const history = useTeamRunHistory({
      api: { listByTeam, get },
      subscribe: (_teamId, handler) => { callback = handler; return vi.fn() },
      setTimeoutImpl: handler => { timers.push(handler); return handler },
    })

    const loading = history.open('10')
    callback?.({ event: 'team_run_completed', data: { runId: '1' } })
    timers.at(-1)?.()
    await vi.waitFor(() => expect(get).toHaveBeenCalledOnce())
    resolveList({ data: [run('1', '2026-01-01', 'running'), run('2', '2026-02-01')] })
    await loading

    expect(history.runs.value.map(item => `${item.id}:${item.status}`)).toEqual(['1:completed', '2:running'])
  })

  it('does not merge a detail projection from another team', async () => {
    const history = useTeamRunHistory({
      api: {
        listByTeam: vi.fn().mockResolvedValue({ data: [] }),
        get: vi.fn().mockResolvedValue({ data: { ...run('9', '2026-03-01'), teamId: '20' } }),
      },
      subscribe: () => vi.fn(),
    })
    await history.open('10')

    expect(await history.refreshRun('9', '10')).toBeNull()
    expect(history.runs.value).toEqual([])
  })

  it('keeps the latest same-run detail when responses resolve in reverse order', async () => {
    const first = deferred<unknown>()
    const second = deferred<unknown>()
    const history = useTeamRunHistory({
      api: {
        listByTeam: vi.fn().mockResolvedValue({ data: [] }),
        get: vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise),
      },
      subscribe: () => vi.fn(),
    })
    await history.open('10')

    const olderRequest = history.refreshRun('1', '10')
    const newerRequest = history.refreshRun('1', '10')
    second.resolve({ data: run('1', '2026-04-01', 'completed') })
    await newerRequest
    first.resolve({ data: run('1', '2026-03-01', 'running') })
    await olderRequest

    expect(history.runs.value.map(item => item.status)).toEqual(['completed'])
  })

  it('ignores an older same-run refresh error after a newer request succeeds', async () => {
    const older = deferred<unknown>()
    const newer = deferred<unknown>()
    const history = useTeamRunHistory({
      api: {
        listByTeam: vi.fn().mockResolvedValue({ data: [] }),
        get: vi.fn().mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise),
      },
      subscribe: () => vi.fn(),
    })
    await history.open('10')

    const olderRequest = history.refreshRun('1', '10')
    const newerRequest = history.refreshRun('1', '10')
    newer.resolve({ data: run('1', '2026-04-01', 'completed') })
    await newerRequest
    older.reject(new Error('stale failure'))
    await olderRequest

    expect(history.runs.value.map(item => item.status)).toEqual(['completed'])
    expect(history.error.value).toBeNull()
  })

  it('invalidates same-run detail when closed and reopened', async () => {
    const stale = deferred<unknown>()
    const get = vi.fn().mockReturnValueOnce(stale.promise)
    const history = useTeamRunHistory({
      api: { listByTeam: vi.fn().mockResolvedValue({ data: [] }), get },
      subscribe: () => vi.fn(),
    })
    await history.open('10')
    const request = history.refreshRun('1', '10')
    history.close()
    await history.open('10')
    stale.resolve({ data: run('1', '2026-03-01', 'completed') })
    await request

    expect(history.runs.value).toEqual([])
  })

  it('loads independently, sorts newest first, and refreshes only the event run', async () => {
    let callback: ((event: { event: string; data: Record<string, unknown> }) => void) | undefined
    const listByTeam = vi.fn().mockResolvedValue({ data: [run('1', '2026-01-01'), run('2', '2026-02-01')] })
    const get = vi.fn().mockResolvedValue({ data: run('1', '2026-03-01', 'completed') })
    const timers: Array<() => void> = []
    const history = useTeamRunHistory({
      api: { listByTeam, get },
      subscribe: (_teamId, handler) => { callback = handler; return vi.fn() },
      setTimeoutImpl: handler => { timers.push(handler); return handler },
      clearTimeoutImpl: vi.fn(),
    })

    await history.open('10')
    expect(history.runs.value.map(item => item.id)).toEqual(['2', '1'])
    callback?.({ event: 'team_task_completed', data: { runId: '1', taskId: '50' } })
    callback?.({ event: 'team_run_completed', data: { runId: '1' } })
    expect(get).not.toHaveBeenCalled()
    timers.at(-1)?.()
    await nextTick()
    await Promise.resolve()

    expect(get).toHaveBeenCalledTimes(1)
    expect(history.runs.value.map(item => `${item.id}:${item.status}`)).toEqual(['1:completed', '2:running'])
  })

  it('ignores stale loads and cleans up subscriptions', async () => {
    let resolveA!: (value: unknown) => void
    const stop = vi.fn()
    const listByTeam = vi.fn()
      .mockReturnValueOnce(new Promise(resolve => { resolveA = resolve }))
      .mockResolvedValueOnce({ data: [{ ...run('2', '2026-02-01'), teamId: '20' }] })
    const history = useTeamRunHistory({
      api: { listByTeam, get: vi.fn() },
      subscribe: () => stop,
    })

    const loadingA = history.open('10')
    await history.open('20')
    resolveA({ data: [run('1', '2026-01-01')] })
    await loadingA
    expect(history.runs.value.map(item => item.id)).toEqual(['2'])

    history.close()
    expect(stop).toHaveBeenCalled()
    expect(history.runs.value).toEqual([])
  })
})

describe('sortTeamRuns', () => {
  it('keeps a stable order when timestamps match or are absent', () => {
    expect(sortTeamRuns([run('1', null), run('2', null), run('3', '2026-03-01')]).map(item => item.id))
      .toEqual(['3', '1', '2'])
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
