<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Document, Link, VideoPause } from '@element-plus/icons-vue'
import type { TeamRun, TeamRunTask } from '@/api'
import TeamRunProgress from './TeamRunProgress.vue'
import TeamRunTaskList from './TeamRunTaskList.vue'
import { buildTeamRunRoute, extractRunDeliverables } from './teamRunPresentation'

const props = withDefaults(defineProps<{
  run: TeamRun
  selectedTaskId?: string | null
  canCancel?: boolean
}>(), {
  selectedTaskId: null,
  canCancel: false,
})

const emit = defineEmits<{
  'select-task': [task: TeamRunTask]
  cancel: [runId: string]
  navigate: [route: ReturnType<typeof buildTeamRunRoute>]
}>()

const { t } = useI18n()
const localTaskId = ref<string | null>(props.selectedTaskId)
watch(() => props.selectedTaskId, value => { localTaskId.value = value })
const selectedTask = computed(() => props.run.tasks.find(task => task.id === localTaskId.value) ?? null)
const deliverables = computed(() => extractRunDeliverables(props.run))
const terminal = computed(() => ['completed', 'partial', 'failed', 'cancelled'].includes(props.run.status))

function selectTask(task: TeamRunTask) {
  localTaskId.value = task.id
  emit('select-task', task)
}
</script>

<template>
  <div class="run-detail">
    <section class="run-detail__summary">
      <div class="run-detail__summary-copy">
        <h4>{{ t('teamRuns.summary') }}</h4>
        <p>{{ run.finalSummary || t('teamRuns.noSummary') }}</p>
        <dl>
          <div>
            <dt>{{ t('teamRuns.objective') }}</dt>
            <dd>{{ run.objective }}</dd>
          </div>
          <div v-if="run.stopReason">
            <dt>{{ t('teamRuns.stopReason') }}</dt>
            <dd>{{ run.stopReason }}</dd>
          </div>
        </dl>
      </div>
      <TeamRunProgress :progress="run.progress" />
    </section>

    <section class="run-detail__section">
      <h4>{{ t('teamRuns.deliverables') }}</h4>
      <div v-if="deliverables.length" class="run-detail__deliverables">
        <a
          v-for="item in deliverables"
          :key="`${item.url}:${item.name}`"
          :href="item.url"
          class="run-detail__deliverable"
          target="_blank"
          rel="noopener"
        >
          <el-icon :size="14"><Document /></el-icon>
          <span>{{ item.name }}</span>
          <el-icon :size="12"><Link /></el-icon>
        </a>
      </div>
      <p v-else class="run-detail__empty">{{ t('teamRuns.noDeliverables') }}</p>
    </section>

    <section class="run-detail__section">
      <h4>{{ t('teamRuns.tasks') }}</h4>
      <TeamRunTaskList
        :tasks="run.tasks"
        :selected-task-id="localTaskId"
        @select-task="selectTask"
      />
    </section>

    <section v-if="selectedTask" class="run-detail__task-detail">
      <div class="run-detail__task-heading">
        <h4>{{ selectedTask.taskNumber }}. {{ selectedTask.subject }}</h4>
        <button
          type="button"
          class="run-detail__link-button"
          @click="emit('navigate', buildTeamRunRoute(run.teamId, run.id, selectedTask.id))"
        >{{ t('teamRuns.openTask') }}</button>
      </div>
      <p v-if="selectedTask.description">{{ selectedTask.description }}</p>
      <dl>
        <div>
          <dt>{{ t('teamRuns.assignee') }}</dt>
          <dd>{{ selectedTask.assigneeAgentId }}</dd>
        </div>
        <div>
          <dt>{{ t('teamRuns.result') }}</dt>
          <dd class="run-detail__result">{{ selectedTask.result || t('teamRuns.noResult') }}</dd>
        </div>
      </dl>
    </section>

    <footer v-if="canCancel && !terminal" class="run-detail__actions">
      <button data-team-run-cancel type="button" class="run-detail__cancel" @click="emit('cancel', run.id)">
        <el-icon :size="14"><VideoPause /></el-icon>
        <span>{{ t('teamRuns.cancel') }}</span>
      </button>
    </footer>
  </div>
</template>

<style scoped>
.run-detail { border-top: 1px solid var(--mc-border-light, #e7ebef); letter-spacing: 0; }
.run-detail h4 { margin: 0; color: var(--mc-text-primary, #1f2937); font-size: 12px; font-weight: 700; }
.run-detail p { margin: 6px 0 0; color: var(--mc-text-secondary, #475569); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.run-detail__summary { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 14px 16px; background: rgba(71, 85, 105, 0.035); }
.run-detail__summary-copy { min-width: 0; flex: 1; }
.run-detail__section, .run-detail__task-detail { padding: 14px 16px; border-top: 1px solid var(--mc-border-light, #e7ebef); }
.run-detail dl { display: grid; gap: 8px; margin: 12px 0 0; }
.run-detail dl > div { display: grid; grid-template-columns: minmax(80px, 120px) minmax(0, 1fr); gap: 10px; }
.run-detail dt { color: var(--mc-text-tertiary, #64748b); font-size: 11px; }
.run-detail dd { min-width: 0; margin: 0; color: var(--mc-text-secondary, #475569); font-size: 12px; overflow-wrap: anywhere; }
.run-detail__deliverables { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.run-detail__deliverable { display: inline-flex; align-items: center; gap: 5px; max-width: 100%; padding: 5px 8px; border: 1px solid #bddbd0; border-radius: 6px; color: #126c4d; font-size: 12px; text-decoration: none; }
.run-detail__deliverable span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-detail__deliverable:hover { background: rgba(27, 143, 104, 0.07); }
.run-detail__empty { color: var(--mc-text-tertiary, #64748b) !important; }
.run-detail__task-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.run-detail__link-button { padding: 0; border: 0; background: transparent; color: #16795a; cursor: pointer; font-size: 12px; }
.run-detail__link-button:focus-visible, .run-detail__cancel:focus-visible { outline: 2px solid #1b8f68; outline-offset: 2px; }
.run-detail__result { white-space: pre-wrap; }
.run-detail__actions { display: flex; justify-content: flex-end; padding: 10px 16px 14px; border-top: 1px solid var(--mc-border-light, #e7ebef); }
.run-detail__cancel { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 5px 10px; border: 1px solid #e6b7b7; border-radius: 6px; background: transparent; color: #b53535; cursor: pointer; font-size: 12px; letter-spacing: 0; }
.run-detail__cancel:hover { background: rgba(193, 61, 61, 0.06); }
@media (max-width: 640px) {
  .run-detail__summary { align-items: center; }
  .run-detail dl > div { grid-template-columns: 1fr; gap: 2px; }
}
</style>
