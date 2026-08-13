import { describe, expect, it, vi } from 'vitest'
import {
  parseTeamSseFrames,
  subscribeTeamEvents,
} from '@/composables/useTeamEvents'

function response(body: string): Response {
  const bytes = new TextEncoder().encode(body)
  let delivered = false
  return {
    ok: true,
    body: {
      getReader: () => ({
        read: async () => {
          if (delivered) return { done: true, value: undefined }
          delivered = true
          return { done: false, value: bytes }
        },
      }),
    },
  } as unknown as Response
}

function dependencies(fetchImpl: typeof fetch) {
  const timers: Array<{ callback: () => void; delay: number }> = []
  return {
    timers,
    options: {
      fetchImpl,
      storage: { getItem: () => null },
      retryBaseMs: 100,
      retryMaxMs: 1_000,
      setTimeoutImpl: (callback: () => void, delay: number) => {
        const timer = { callback, delay }
        timers.push(timer)
        return timer
      },
      clearTimeoutImpl: vi.fn(),
    },
  }
}

describe('parseTeamSseFrames', () => {
  it('parses CRLF frames with ids and multiline data while retaining partial input', () => {
    const parsed = parseTeamSseFrames(
      'id: 9007199254740993\r\nevent: team_run_progress\r\n'
      + 'data: first line\r\ndata: second line\r\n\r\nid: 2\r\ndata: partial',
    )

    expect(parsed.frames).toEqual([{
      id: '9007199254740993',
      event: 'team_run_progress',
      data: 'first line\nsecond line',
    }])
    expect(parsed.remainder).toBe('id: 2\r\ndata: partial')
  })
})

describe('subscribeTeamEvents', () => {
  it('reconnects with Last-Event-ID and de-duplicates replayed ids', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response('id: 7\nevent: team_task_progress\ndata: {"step":1}\n\n'))
      .mockResolvedValueOnce(response(
        'id: 7\nevent: team_task_progress\ndata: {"step":1}\n\n'
        + 'id: 8\r\nevent: team_run_progress\r\ndata: {"step":2}\r\n\r\n',
      )) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const events: Array<{ id?: string; event: string }> = []
    const stop = subscribeTeamEvents('9007199254740995', event => events.push(event), options)

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    timers.shift()!.callback()
    await vi.waitFor(() => expect(events).toHaveLength(2))

    expect(events.map(event => event.id)).toEqual(['7', '8'])
    const secondRequest = vi.mocked(fetchImpl).mock.calls[1][1] as RequestInit
    expect(secondRequest.headers).toMatchObject({ 'Last-Event-ID': '7' })
    stop()
  })

  it('uses exponential backoff for consecutive disconnects', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('offline')) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const stop = subscribeTeamEvents('10', vi.fn(), options)

    await vi.waitFor(() => expect(timers.map(timer => timer.delay)).toEqual([100]))
    timers[0].callback()
    await vi.waitFor(() => expect(timers.map(timer => timer.delay)).toEqual([100, 200]))
    timers[1].callback()
    await vi.waitFor(() => expect(timers.map(timer => timer.delay)).toEqual([100, 200, 400]))
    stop()
  })

  it('delivers a higher id from a recreated server stream', async () => {
    const highId = '1850000000000000000'
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response('id: 7\nevent: team_task_progress\ndata: {"step":1}\n\n'))
      .mockResolvedValueOnce(response(
        `id: ${highId}\nevent: team_run_progress\ndata: {"step":2}\n\n`,
      )) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const ids: string[] = []
    const stop = subscribeTeamEvents('10', event => ids.push(event.id!), options)

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    timers.shift()!.callback()
    await vi.waitFor(() => expect(ids).toEqual(['7', highId]))

    stop()
  })

  it('bounds the seen id cache without moving Last-Event-ID backwards', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(
        'id: 100\nevent: update\ndata: {"step":1}\n\n'
        + 'id: 101\nevent: update\ndata: {"step":2}\n\n'
        + 'id: 102\nevent: update\ndata: {"step":3}\n\n'
        + 'id: 100\nevent: update\ndata: {"step":4}\n\n',
      ))
      .mockResolvedValueOnce(response('')) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const ids: string[] = []
    const stop = subscribeTeamEvents('10', event => ids.push(event.id!), {
      ...options,
      seenEventLimit: 2,
    })

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    expect(ids).toEqual(['100', '101', '102', '100'])
    timers.shift()!.callback()
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2))

    const secondRequest = vi.mocked(fetchImpl).mock.calls[1][1] as RequestInit
    expect(secondRequest.headers).toMatchObject({ 'Last-Event-ID': '102' })
    stop()
  })

  it('does not reconnect after aborting an active request', async () => {
    const fetchImpl = vi.fn((_url: string | URL | Request, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      })) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const stop = subscribeTeamEvents('10', vi.fn(), options)

    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledOnce())
    stop()
    await Promise.resolve()

    expect(timers).toHaveLength(0)
    expect(fetchImpl).toHaveBeenCalledOnce()
  })
})
