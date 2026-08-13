import { computed, getCurrentInstance, onBeforeUnmount, ref } from 'vue'
import { teamRunApi, type TeamRun } from '@/api'
import { subscribeTeamEvents, type TeamBoardEvent } from './useTeamEvents'

export function sortTeamRuns(runs: readonly TeamRun[]): TeamRun[] {
  return runs
    .map((run, index) => ({ run, index }))
    .sort((a, b) => {
      const aTime = a.run.createTime ? Date.parse(a.run.createTime) : Number.NaN
      const bTime = b.run.createTime ? Date.parse(b.run.createTime) : Number.NaN
      const safeA = Number.isFinite(aTime) ? aTime : Number.NEGATIVE_INFINITY
      const safeB = Number.isFinite(bTime) ? bTime : Number.NEGATIVE_INFINITY
      return safeB - safeA || a.index - b.index
    })
    .map(entry => entry.run)
}

interface RunHistoryApi {
  listByTeam(teamId: string): Promise<unknown>
  get(runId: string): Promise<unknown>
}

export interface TeamRunHistoryOptions {
  api?: RunHistoryApi
  subscribe?: typeof subscribeTeamEvents
  debounceMs?: number
  setTimeoutImpl?: (handler: () => void, delay: number) => unknown
  clearTimeoutImpl?: (handle: unknown) => void
}

function responseData<T>(response: unknown): T {
  return (response as { data: T }).data
}

export function useTeamRunHistory(options: TeamRunHistoryOptions = {}) {
  const api = options.api ?? teamRunApi
  const subscribe = options.subscribe ?? subscribeTeamEvents
  const debounceMs = options.debounceMs ?? 250
  const setTimeoutImpl = options.setTimeoutImpl ?? ((handler, delay) => globalThis.setTimeout(handler, delay))
  const clearTimeoutImpl = options.clearTimeoutImpl ?? (handle => globalThis.clearTimeout(handle as number))
  const runs = ref<TeamRun[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const teamId = ref<string | null>(null)
  const selectedRunId = ref<string | null>(null)
  const selectedTaskId = ref<string | null>(null)
  const selectedRun = computed(() => runs.value.find(run => run.id === selectedRunId.value) ?? null)
  const refreshTimers = new Map<string, unknown>()
  const runRevisions = new Map<string, number>()
  const runRequestSequences = new Map<string, number>()
  let generation = 0
  let revision = 0
  let unsubscribe: (() => void) | null = null

  function merge(run: TeamRun) {
    const next = runs.value.filter(item => item.id !== run.id)
    runs.value = sortTeamRuns([...next, run])
    runRevisions.set(run.id, ++revision)
  }

  function mergeList(list: TeamRun[], requestRevision: number, expectedTeamId: string) {
    const currentById = new Map(runs.value.map(run => [run.id, run]))
    const merged = list
      .filter(run => run.teamId === expectedTeamId)
      .map(run => (runRevisions.get(run.id) ?? 0) > requestRevision ? currentById.get(run.id)! : run)
    const listedIds = new Set(list.map(run => run.id))
    for (const current of runs.value) {
      if (!listedIds.has(current.id) && (runRevisions.get(current.id) ?? 0) > requestRevision) {
        merged.push(current)
      }
    }
    runs.value = sortTeamRuns(merged)
  }

  async function refreshRun(
    runId: string,
    expectedTeamId = teamId.value,
    expectedGeneration = generation,
  ): Promise<TeamRun | null> {
    const requestSequence = (runRequestSequences.get(runId) ?? 0) + 1
    runRequestSequences.set(runId, requestSequence)
    const isLatestRequest = () => expectedGeneration === generation
      && runRequestSequences.get(runId) === requestSequence
    try {
      const response = await api.get(runId)
      if (!isLatestRequest()) return null
      const run = responseData<TeamRun>(response)
      if (!expectedTeamId || run.teamId !== expectedTeamId) return null
      merge(run)
      error.value = null
      return run
    } catch (cause) {
      if (isLatestRequest()) error.value = cause instanceof Error ? cause.message : String(cause)
      return null
    }
  }

  function scheduleRunRefresh(runId: string, expectedTeamId: string, expectedGeneration: number) {
    const current = refreshTimers.get(runId)
    if (current !== undefined) clearTimeoutImpl(current)
    refreshTimers.set(runId, setTimeoutImpl(() => {
      refreshTimers.delete(runId)
      void refreshRun(runId, expectedTeamId, expectedGeneration)
    }, debounceMs))
  }

  function onEvent(event: TeamBoardEvent, expectedGeneration: number) {
    if (expectedGeneration !== generation) return
    if (!event.event.startsWith('team_run_') && !event.event.startsWith('team_task_')) return
    const runId = typeof event.data.runId === 'string' ? event.data.runId : null
    if (runId && teamId.value) scheduleRunRefresh(runId, teamId.value, expectedGeneration)
  }

  async function open(nextTeamId: string) {
    const expectedGeneration = ++generation
    unsubscribe?.()
    unsubscribe = null
    refreshTimers.forEach(clearTimeoutImpl)
    refreshTimers.clear()
    teamId.value = nextTeamId
    runs.value = []
    runRevisions.clear()
    runRequestSequences.clear()
    revision = 0
    loading.value = true
    error.value = null
    unsubscribe = subscribe(nextTeamId, event => onEvent(event, expectedGeneration))
    const requestRevision = revision
    try {
      const response = await api.listByTeam(nextTeamId)
      if (expectedGeneration === generation) {
        mergeList(responseData<TeamRun[]>(response) ?? [], requestRevision, nextTeamId)
      }
    } catch (cause) {
      if (expectedGeneration === generation) error.value = cause instanceof Error ? cause.message : String(cause)
    } finally {
      if (expectedGeneration === generation) loading.value = false
    }
  }

  async function refresh() {
    if (teamId.value) await open(teamId.value)
  }

  function select(runId: string | null, taskId: string | null = null) {
    selectedRunId.value = runId
    selectedTaskId.value = taskId
  }

  function close() {
    generation++
    unsubscribe?.()
    unsubscribe = null
    refreshTimers.forEach(clearTimeoutImpl)
    refreshTimers.clear()
    teamId.value = null
    runs.value = []
    runRevisions.clear()
    runRequestSequences.clear()
    loading.value = false
    error.value = null
    select(null)
  }

  if (getCurrentInstance()) onBeforeUnmount(close)

  return { runs, loading, error, selectedRun, selectedRunId, selectedTaskId, open, refresh, refreshRun, select, close }
}
