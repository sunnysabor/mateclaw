import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LiveRunCard, LiveSnapshot, TeamRun, TeamRunTask } from '@/api'
import { teamApi, teamRunApi } from '@/api'
import { buildAgentWorkerChatRoute, projectAgentRunGroups, useAgentRunGroups } from '../useAgentRunGroups'

vi.mock('@/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api')>()
  return {
    ...original,
    teamApi: { ...original.teamApi, list: vi.fn() },
    teamRunApi: { ...original.teamRunApi, listByTeam: vi.fn(), get: vi.fn() },
  }
})

const live = (conversationId: string, stuckReason: string | null = null): LiveRunCard => ({
  conversationId, agentId: 2, agentName: conversationId, agentIcon: null, username: null,
  currentPhase: 'tools', runningToolName: null, waitingReason: null, done: false, stopRequested: false,
  firstTokenReceived: true, subscriberCount: 1, queueLen: 0, ageMs: 60_000, msSinceLastEvent: 1_000,
  stuckReason, orphan: false, subagentCount: 0,
})
const task = (id: string, taskNumber: number, status: string, conversationId: string | null, blockedBy: string | null = null): TeamRunTask => ({
  id, teamId: '10', runId: '20', taskNumber, subject: `Task ${id}`, description: null,
  status, priority: 0, taskType: 'execution', assigneeAgentId: `agent-${id}`, ownerAgentId: null,
  blockedBy, requireApproval: false, progressPercent: null, progressStep: null, result: null, reason: null,
  conversationId, metadata: null, createTime: null, updateTime: null,
})
const run = (status: TeamRun['status'], tasks: TeamRunTask[]): TeamRun => ({
  id: '20', teamId: '10', workspaceId: '1', leadAgentId: 'lead-agent', leadConversationId: 'lead-conv',
  originMessageId: null, title: 'Launch research', objective: 'Prepare launch', status, finalSummary: null,
  stopReason: null, metadata: null, startedAt: '2026-08-13T10:00:00Z', completedAt: null,
  createTime: '2026-08-13T10:00:00Z', updateTime: '2026-08-13T10:01:00Z',
  progress: { total: tasks.length, done: 0, failed: 0, inReview: 0, percent: 20 }, tasks,
})
const snapshot = (runs: LiveRunCard[]): LiveSnapshot => ({
  runs, subagents: [], timestamp: 1, summary: { running: runs.length, stuck: 0, orphan: 0, queued: 0, subagentsActive: 0 },
})

describe('projectAgentRunGroups', () => {
  it('joins only explicit task and lead conversation ids and keeps non-team sessions separate', () => {
    const tasks = [
      task('1', 1, 'in_progress', 'worker-active'),
      task('2', 2, 'blocked', null, '["1"]'),
      task('3', 3, 'in_review', 'worker-review'),
      task('4', 4, 'in_progress', 'worker-stuck'),
      task('5', 5, 'cancelled', null),
    ]
    const result = projectAgentRunGroups(snapshot([
      live('lead-conv'), live('worker-active'), live('worker-review'), live('worker-stuck', 'idle_silent'),
      live('Task 1 child guessed name'), live('unrelated'),
    ]), [run('running', tasks)])

    expect(result.groups).toHaveLength(1)
    expect(result.groups[0].leadRuntime?.conversationId).toBe('lead-conv')
    expect(result.groups[0].workers.map(worker => `${worker.task.id}:${worker.state}`)).toEqual([
      '1:active', '2:waiting', '3:review', '4:stuck', '5:cancelled',
    ])
    expect(result.ungrouped.map(item => item.conversationId)).toEqual(['Task 1 child guessed name', 'unrelated'])
    expect(buildAgentWorkerChatRoute(result.groups[0], result.groups[0].workers[0])).toEqual({
      path: '/chat',
      query: {
        conversationId: 'worker-active', agentId: 'agent-1', teamRunId: '20', taskId: '1',
        teamId: '10', leadConversationId: 'lead-conv',
      },
    })
  })

  it.each([
    ['finalizing', 'finalizing'],
    ['cancelled', 'cancelled'],
  ] as const)('projects %s run state as %s', (status, expected) => {
    expect(projectAgentRunGroups(snapshot([]), [run(status, [])]).groups[0].state).toBe(expected)
  })
})

describe('useAgentRunGroups hydration priority', () => {
  beforeEach(() => vi.clearAllMocks())

  it('keeps an explicit route run when a later snapshot refresh completes first', async () => {
    const routeDetail = deferred<unknown>()
    vi.mocked(teamApi.list).mockResolvedValue({ data: [{ team: { id: '10' } }] } as never)
    vi.mocked(teamRunApi.listByTeam)
      .mockResolvedValueOnce({ data: [] } as never)
      .mockResolvedValueOnce({ data: [run('running', [])] } as never)
    vi.mocked(teamRunApi.get).mockReturnValue(routeDetail.promise as never)
    const groups = useAgentRunGroups(ref(snapshot([])))

    const routeLoad = groups.ensureRun('historical', 1)
    const pollLoad = groups.refreshForSnapshot()
    await pollLoad
    routeDetail.resolve({ data: { ...run('cancelled', [task('ended', 1, 'cancelled', 'worker-ended')]), id: 'historical' } })
    await routeLoad

    expect(groups.runs.value.map(item => item.id)).toContain('historical')
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
