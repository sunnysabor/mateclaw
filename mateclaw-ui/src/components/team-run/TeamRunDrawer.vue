<script setup lang="ts">
import { Close } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import type { TeamRun, TeamRunTask } from '@/api'
import TeamRunDetail from './TeamRunDetail.vue'
import TeamRunStatus from './TeamRunStatus.vue'
import type { TeamRunRoute } from './teamRunPresentation'

withDefaults(defineProps<{
  run: TeamRun | null
  open?: boolean
  selectedTaskId?: string | null
  canCancel?: boolean
}>(), { open: false, selectedTaskId: null, canCancel: false })

const emit = defineEmits<{
  close: []
  cancel: [runId: string]
  'select-task': [task: TeamRunTask]
  navigate: [route: TeamRunRoute]
}>()
const { t } = useI18n()
</script>

<template>
  <div v-if="open && run" class="run-drawer-layer" @click.self="emit('close')">
    <aside class="run-drawer" role="dialog" aria-modal="true" :aria-label="run.title">
      <header class="run-drawer__header">
        <div>
          <h2>{{ run.title }}</h2>
          <TeamRunStatus :status="run.status" :started-at="run.startedAt" :completed-at="run.completedAt" show-duration />
        </div>
        <button data-team-run-drawer-close type="button" :aria-label="t('teamRuns.close')" @click="emit('close')">
          <el-icon :size="17"><Close /></el-icon>
        </button>
      </header>
      <p v-if="run.status === 'partial'" class="run-drawer__notice is-partial">{{ t('teamRuns.partialNotice') }}</p>
      <p v-if="run.status === 'cancelled'" class="run-drawer__notice">{{ run.stopReason || t('teamRuns.status.cancelled') }}</p>
      <TeamRunDetail
        :run="run"
        :selected-task-id="selectedTaskId"
        :can-cancel="canCancel"
        @select-task="emit('select-task', $event)"
        @cancel="emit('cancel', $event)"
        @navigate="emit('navigate', $event)"
      />
    </aside>
  </div>
</template>

<style scoped>
.run-drawer-layer { position: fixed; z-index: 1200; inset: 0; display: flex; justify-content: flex-end; background: rgba(15, 23, 42, 0.28); }
.run-drawer { width: min(620px, 94vw); height: 100%; overflow-y: auto; border-left: 1px solid var(--mc-border); background: var(--mc-bg-elevated, #fff); box-shadow: -12px 0 28px rgba(15, 23, 42, 0.14); letter-spacing: 0; }
.run-drawer__header { position: sticky; z-index: 1; top: 0; display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px; border-bottom: 1px solid var(--mc-border); background: var(--mc-bg-elevated, #fff); }
.run-drawer__header h2 { margin: 0 0 6px; color: var(--mc-text-primary); font-size: 16px; overflow-wrap: anywhere; }
.run-drawer__header button { display: grid; width: 30px; height: 30px; flex: none; place-items: center; border: 0; border-radius: 6px; background: transparent; color: var(--mc-text-secondary); cursor: pointer; }
.run-drawer__header button:hover { background: var(--mc-bg-sunken); }
.run-drawer__header button:focus-visible { outline: 2px solid #16835b; outline-offset: 2px; }
.run-drawer__notice { margin: 0; padding: 9px 16px; border-bottom: 1px solid var(--mc-border-light); background: rgba(71, 85, 105, 0.06); color: var(--mc-text-secondary); font-size: 12px; }
.run-drawer__notice.is-partial { background: rgba(185, 108, 8, 0.08); color: #8a5108; }
</style>
