<script setup lang="ts">
import { ChatDotRound, Grid, Lock, User } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import {
  buildAgentRunRoute,
  buildChatRunRoute,
  buildTeamRunRoute,
  type TeamRunRoute,
} from '@/components/team-run/teamRunPresentation'

const props = defineProps<{
  runId: string
  taskId: string
  teamId?: string
  leadConversationId?: string
}>()

const emit = defineEmits<{ navigate: [route: TeamRunRoute] }>()
const { t } = useI18n()
</script>

<template>
  <aside class="worker-banner" role="status">
    <el-icon class="worker-banner__lock"><Lock /></el-icon>
    <span class="worker-banner__copy">
      <strong>{{ t('teamRuns.workerReadOnly') }}</strong>
      <span>{{ t('teamRuns.workerReadOnlyDescription') }}</span>
    </span>
    <span class="worker-banner__actions">
      <button
        v-if="leadConversationId"
        type="button"
        @click="emit('navigate', buildChatRunRoute(runId, leadConversationId))"
      >
        <el-icon><ChatDotRound /></el-icon><span>{{ t('teamRuns.backToLead') }}</span>
      </button>
      <button
        v-if="teamId"
        type="button"
        @click="emit('navigate', buildTeamRunRoute(teamId, runId, taskId))"
      >
        <el-icon><Grid /></el-icon><span>{{ t('teamRuns.openInTeams') }}</span>
      </button>
      <button type="button" @click="emit('navigate', buildAgentRunRoute(runId, taskId))">
        <el-icon><User /></el-icon><span>{{ t('teamRuns.openInAgents') }}</span>
      </button>
    </span>
  </aside>
</template>

<style scoped>
.worker-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 12px 0;
  padding: 9px 12px;
  border: 1px solid var(--mc-border, #d9e1e7);
  border-left: 3px solid #b96c08;
  border-radius: 6px;
  background: var(--mc-panel, #fff);
  color: var(--mc-text-primary, #1f2937);
  letter-spacing: 0;
}
.worker-banner__lock { flex: none; color: #b96c08; }
.worker-banner__copy { display: grid; min-width: 0; gap: 2px; font-size: 12px; }
.worker-banner__copy strong { font-size: 13px; }
.worker-banner__copy span { color: var(--mc-text-secondary, #64748b); }
.worker-banner__actions { display: flex; flex: none; gap: 6px; margin-left: auto; }
.worker-banner__actions button { display: inline-flex; align-items: center; gap: 5px; min-height: 30px; padding: 4px 8px; border: 1px solid var(--mc-border, #d9e1e7); border-radius: 5px; background: transparent; color: inherit; cursor: pointer; letter-spacing: 0; }
.worker-banner__actions button:hover { border-color: #1b8f68; color: #167454; }
.worker-banner__actions button:focus-visible { outline: 2px solid #1b8f68; outline-offset: 1px; }
@media (max-width: 720px) {
  .worker-banner { align-items: flex-start; flex-wrap: wrap; }
  .worker-banner__actions { width: 100%; margin-left: 24px; overflow-x: auto; }
}
</style>
