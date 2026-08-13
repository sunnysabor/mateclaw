import { ref } from 'vue'
import type { LiveSnapshot } from '@/api'

interface Dependencies {
  load: () => Promise<unknown>
  refreshRuns: () => Promise<unknown>
}

export function useLiveSnapshot({ load, refreshRuns }: Dependencies) {
  const snapshot = ref<LiveSnapshot | null>(null)
  const loading = ref(true)
  const error = ref<unknown>(null)
  let sequence = 0

  async function refresh() {
    const request = ++sequence
    try {
      const response = await load() as { data?: LiveSnapshot }
      if (request !== sequence) return false
      snapshot.value = response?.data ?? response as LiveSnapshot
      error.value = null
      await refreshRuns()
      return request === sequence
    } catch (cause) {
      if (request === sequence) error.value = cause
      return false
    } finally {
      if (request === sequence) loading.value = false
    }
  }

  function invalidate() {
    sequence++
  }

  return { snapshot, loading, error, refresh, invalidate, close: invalidate }
}
