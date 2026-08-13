import { getCurrentScope, onScopeDispose, ref, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue'
import { teamRunApi, type TeamRun } from '@/api'
import { subscribeTeamEvents, type TeamBoardEvent } from '@/composables/useTeamEvents'

type ApiResult<T> = T | { data: T }

export interface TeamRunsDependencies {
  listByConversation: (conversationId: string) => Promise<ApiResult<TeamRun[]>>
  getRun: (runId: string) => Promise<ApiResult<TeamRun>>
  subscribe: (teamId: string, onEvent: (event: TeamBoardEvent) => void) => () => void
}

export interface UseTeamRunsOptions {
  linkedRunId?: MaybeRefOrGetter<string | undefined>
  dependencies?: TeamRunsDependencies
}

const defaultDependencies: TeamRunsDependencies = {
  listByConversation: conversationId => teamRunApi.listByConversation(conversationId),
  getRun: runId => teamRunApi.get(runId),
  subscribe: subscribeTeamEvents,
}

function dataOf<T>(result: ApiResult<T>): T {
  return result !== null && typeof result === 'object' && 'data' in result
    ? (result as { data: T }).data
    : result as T
}

function uniqueRuns(runs: TeamRun[]): TeamRun[] {
  return Array.from(new Map(runs.map(run => [run.id, run])).values())
}

export function useTeamRuns(
  conversationId: MaybeRefOrGetter<string>,
  options: UseTeamRunsOptions = {},
): {
  runs: Ref<TeamRun[]>
  loading: Ref<boolean>
  error: Ref<unknown>
  refresh: () => Promise<void>
  refreshRun: (runId: string) => Promise<void>
  stop: () => void
} {
  const dependencies = options.dependencies ?? defaultDependencies
  const runs = ref<TeamRun[]>([])
  const loading = ref(false)
  const error = ref<unknown>(null)
  const subscriptions = new Map<string, () => void>()
  const inFlight = new Map<string, Promise<void>>()
  let generation = 0
  let stopped = false

  const cleanupSubscriptions = () => {
    subscriptions.forEach(cleanup => cleanup())
    subscriptions.clear()
  }

  const ensureSubscriptions = () => {
    const subscriptionGeneration = generation
    for (const teamId of new Set(runs.value.map(run => run.teamId))) {
      if (subscriptions.has(teamId)) continue
      subscriptions.set(teamId, dependencies.subscribe(teamId, (event) => {
        if (!stopped && subscriptionGeneration === generation) handleEvent(event)
      }))
    }
  }

  const replaceRun = (nextRun: TeamRun) => {
    const index = runs.value.findIndex(run => run.id === nextRun.id)
    if (index < 0) runs.value = [...runs.value, nextRun]
    else runs.value = runs.value.map((run, position) => position === index ? nextRun : run)
    ensureSubscriptions()
  }

  const refreshRun = (runId: string): Promise<void> => {
    const activeGeneration = generation
    const requestKey = `${activeGeneration}:${runId}`
    const existing = inFlight.get(requestKey)
    if (existing) return existing
    const request = dependencies.getRun(runId)
      .then((result) => {
        if (!stopped && activeGeneration === generation) replaceRun(dataOf(result))
      })
      .catch((cause) => {
        if (!stopped && activeGeneration === generation) error.value = cause
      })
      .finally(() => { inFlight.delete(requestKey) })
    inFlight.set(requestKey, request)
    return request
  }

  function handleEvent(event: TeamBoardEvent) {
    const runId = typeof event.data.runId === 'string' ? event.data.runId : undefined
    if (!runId) return
    const index = runs.value.findIndex(run => run.id === runId)
    const eventConversationId = typeof event.data.leadConversationId === 'string'
      ? event.data.leadConversationId
      : undefined
    const linkedRunId = options.linkedRunId ? toValue(options.linkedRunId) : undefined
    if (index < 0 && runId !== linkedRunId && eventConversationId !== toValue(conversationId)) return
    if (index >= 0) {
      const current = runs.value[index]
      const status = typeof event.data.status === 'string' ? event.data.status as TeamRun['status'] : current.status
      const progress = event.data.progress && typeof event.data.progress === 'object'
        ? event.data.progress as TeamRun['progress']
        : current.progress
      runs.value = runs.value.map((run, position) => position === index
        ? { ...current, status, progress }
        : run)
    }
    void refreshRun(runId)
  }

  const refresh = async () => {
    const activeGeneration = ++generation
    cleanupSubscriptions()
    inFlight.clear()
    loading.value = true
    error.value = null
    try {
      const id = toValue(conversationId)
      const linkedRunId = options.linkedRunId ? toValue(options.linkedRunId) : undefined
      const [listedResult, linkedResult] = await Promise.allSettled([
        id ? dependencies.listByConversation(id) : Promise.resolve([]),
        linkedRunId ? dependencies.getRun(linkedRunId) : Promise.resolve(undefined),
      ])
      if (stopped || activeGeneration !== generation) return
      const listed = listedResult.status === 'fulfilled' ? dataOf(listedResult.value) : []
      const linked = linkedResult.status === 'fulfilled' && linkedResult.value
        ? dataOf(linkedResult.value)
        : undefined
      runs.value = uniqueRuns([...listed, ...(linked ? [linked] : [])])
      if (linkedResult.status === 'rejected') error.value = linkedResult.reason
      else if (listedResult.status === 'rejected') error.value = listedResult.reason
      ensureSubscriptions()
    } catch (cause) {
      if (!stopped && activeGeneration === generation) error.value = cause
    } finally {
      if (!stopped && activeGeneration === generation) loading.value = false
    }
  }

  const stopWatch = watch(
    [() => toValue(conversationId), () => options.linkedRunId ? toValue(options.linkedRunId) : undefined],
    () => { void refresh() },
    { immediate: true },
  )

  const stop = () => {
    if (stopped) return
    stopped = true
    generation += 1
    stopWatch()
    cleanupSubscriptions()
  }
  if (getCurrentScope()) onScopeDispose(stop)

  return { runs, loading, error, refresh, refreshRun, stop }
}
