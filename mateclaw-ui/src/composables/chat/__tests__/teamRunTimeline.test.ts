import { describe, expect, it } from 'vitest'
import { assembleTeamRunTimeline } from '../teamRunTimeline'
import type { TeamRun } from '@/api'
import type { Message } from '@/types'

const message = (id: string, role: Message['role'], content: string, metadata?: unknown): Message => ({
  id, conversationId: 'lead', role, content, contentParts: [], metadata: metadata as never,
})

const run = (id: string, originMessageId: string | null): TeamRun => ({
  id, teamId: `team-${id}`, workspaceId: 'workspace', leadAgentId: 'lead-agent',
  leadConversationId: 'lead', originMessageId, title: `Run ${id}`, objective: 'Work',
  status: 'running', finalSummary: null, stopReason: null, metadata: null,
  startedAt: null, completedAt: null, createTime: null, updateTime: null,
  progress: { total: 0, done: 0, failed: 0, inReview: 0, percent: 0 }, tasks: [],
})

const keys = (items: ReturnType<typeof assembleTeamRunTimeline>) => items.map(item =>
  item.type === 'message' ? `m:${item.message.id}` : `r:${item.run.id}`)

describe('assembleTeamRunTimeline', () => {
  it('keeps the origin user message and anchors its run immediately after it', () => {
    const messages = [
      message('1', 'assistant', 'before'),
      message('2', 'user', 'delegate'),
      message('3', 'assistant', 'after'),
    ]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', '2')]))).toEqual([
      'm:1', 'm:2', 'r:10', 'm:3',
    ])
  })

  it('absorbs only same-run bookkeeping while preserving unrelated order', () => {
    const messages = [
      message('1', 'user', 'delegate'),
      message('2', 'user', 'protocol', { type: 'team_announce', runId: '10', taskId: '101' }),
      message('3', 'assistant', 'reply', { type: 'team_announce_reply', runId: '10', taskId: '101' }),
      message('4', 'assistant', 'unrelated'),
      message('5', 'user', '[System Message] legacy settlement'),
      message('6', 'user', 'unknown run', { type: 'team_announce', runId: '99' }),
    ]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', '1')]))).toEqual([
      'm:1', 'r:10', 'm:4', 'm:5', 'm:6',
    ])
  })

  it('supports multiple runs sharing an origin and de-duplicates projections by string id', () => {
    const messages = [message('1', 'user', 'delegate'), message('2', 'assistant', 'done')]

    expect(keys(assembleTeamRunTimeline(messages, [
      run('10', '1'), run('11', '1'), run('10', '1'),
    ]))).toEqual(['m:1', 'r:10', 'r:11', 'm:2'])
  })

  it('uses the first bookkeeping position when paginated history omits the origin', () => {
    const messages = [
      message('20', 'assistant', 'older visible'),
      message('21', 'user', 'run update', { type: 'team_announce', runId: '10' }),
      message('22', 'assistant', 'later'),
    ]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', '2')]))).toEqual([
      'm:20', 'r:10', 'm:22',
    ])
  })

  it('appends runs with neither a visible origin nor bookkeeping without changing messages', () => {
    const messages = [message('20', 'assistant', 'history'), message('21', 'user', 'question')]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', null)]))).toEqual([
      'm:20', 'm:21', 'r:10',
    ])
    expect(messages).toHaveLength(2)
  })
})
