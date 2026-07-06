<template>
  <div class="mc-page-shell dify-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner">
        <div class="settings-section dify-section">
          <div class="section-header">
            <div>
              <h2 class="section-title">{{ t('difyWorkflow.title') }}</h2>
              <p class="section-desc">{{ t('difyWorkflow.desc') }}</p>
            </div>
            <div class="section-actions">
              <button class="btn-secondary" :disabled="loading || saving" @click="load">
                {{ t('common.reset') }}
              </button>
              <button class="btn-primary" :disabled="saving" @click="() => saveConfig()">
                {{ saving ? t('common.saving') : t('common.save') }}
              </button>
            </div>
          </div>

          <div class="risk-banner">
            <strong>{{ t('difyWorkflow.warningTitle') }}</strong>
            <span>{{ t('difyWorkflow.warningBody', { baseUrl: form.baseUrl }) }}</span>
          </div>

          <div v-if="loading" class="state-panel">{{ t('common.loading') }}</div>
          <div v-else class="dify-grid">
            <section class="settings-card config-panel">
              <div class="card-heading">
                <div>
                  <h3>{{ t('difyWorkflow.configTitle') }}</h3>
                  <p>{{ t('difyWorkflow.configDesc') }}</p>
                </div>
                <span class="status-pill" :class="form.enabled ? 'status-on' : 'status-off'">
                  {{ form.enabled ? t('difyWorkflow.enabled') : t('difyWorkflow.disabled') }}
                </span>
              </div>

              <div class="form-stack">
                <label class="field">
                  <span>{{ t('difyWorkflow.fields.name') }}</span>
                  <input v-model.trim="form.name" class="form-input" />
                </label>
                <label class="field">
                  <span>{{ t('difyWorkflow.fields.description') }}</span>
                  <input v-model.trim="form.description" class="form-input" />
                </label>
                <label class="field">
                  <span>{{ t('difyWorkflow.fields.baseUrl') }}</span>
                  <input v-model="form.baseUrl" class="form-input mono" readonly />
                </label>
                <label class="field">
                  <span>{{ t('difyWorkflow.fields.apiKey') }}</span>
                  <input
                    v-model.trim="form.apiKey"
                    class="form-input mono"
                    type="password"
                    autocomplete="off"
                    :placeholder="apiKeyPlaceholder"
                  />
                  <small>{{ t('difyWorkflow.fields.apiKeyHint') }}</small>
                </label>
                <label class="toggle-row">
                  <span>
                    <strong>{{ t('difyWorkflow.fields.enabled') }}</strong>
                    <small>{{ t('difyWorkflow.fields.enabledHint') }}</small>
                  </span>
                  <span class="toggle-switch">
                    <input v-model="form.enabled" type="checkbox" />
                    <span class="toggle-slider"></span>
                  </span>
                </label>
                <label class="field">
                  <span>{{ t('difyWorkflow.fields.inputSchemaJson') }}</span>
                  <textarea v-model="form.inputSchemaJson" class="form-input mono textarea" rows="7" />
                </label>
                <label class="field">
                  <span>{{ t('difyWorkflow.fields.defaultInputsJson') }}</span>
                  <textarea v-model="form.defaultInputsJson" class="form-input mono textarea" rows="7" />
                </label>
              </div>
            </section>

            <section class="settings-card run-panel">
              <div class="card-heading">
                <div>
                  <h3>{{ t('difyWorkflow.runTitle') }}</h3>
                  <p>{{ t('difyWorkflow.runDesc') }}</p>
                </div>
              </div>

              <label class="field">
                <span>{{ t('difyWorkflow.fields.runInputsJson') }}</span>
                <textarea v-model="runInputsJson" class="form-input mono textarea run-inputs" rows="12" />
              </label>

              <div class="run-actions">
                <button
                  class="btn-secondary"
                  :disabled="testRunning || manualRunning || !canRun"
                  @click="testRun"
                >
                  {{ testRunning ? t('difyWorkflow.testing') : t('difyWorkflow.testRun') }}
                </button>
                <button
                  class="btn-primary"
                  :disabled="testRunning || manualRunning || !canRun"
                  @click="manualRun"
                >
                  {{ manualRunning ? t('difyWorkflow.running') : t('difyWorkflow.manualRun') }}
                </button>
              </div>

              <div class="last-test">
                <div class="last-test__item">
                  <span>{{ t('difyWorkflow.lastTestStatus') }}</span>
                  <strong>{{ config?.lastTestStatus || '-' }}</strong>
                </div>
                <div class="last-test__item">
                  <span>{{ t('difyWorkflow.lastTestAt') }}</span>
                  <strong>{{ formatTime(config?.lastTestAt) }}</strong>
                </div>
                <p v-if="config?.lastTestError" class="last-test__error">{{ config.lastTestError }}</p>
              </div>
            </section>
          </div>

          <section class="settings-card runs-panel">
            <div class="card-heading">
              <div>
                <h3>{{ t('difyWorkflow.runsTitle') }}</h3>
                <p>{{ t('difyWorkflow.runsDesc') }}</p>
              </div>
              <button class="btn-secondary" :disabled="runsLoading" @click="loadRuns">
                {{ runsLoading ? t('common.loading') : t('common.refresh') }}
              </button>
            </div>

            <div v-if="!runs.length" class="empty-note">{{ t('difyWorkflow.noRuns') }}</div>
            <div v-else class="runs-layout">
              <div class="runs-list">
                <button
                  v-for="run in runs"
                  :key="String(run.id)"
                  class="run-row"
                  :class="{ active: String(selectedRun?.id) === String(run.id) }"
                  @click="selectedRun = run"
                >
                  <span class="run-state" :class="stateClass(run.state)">{{ run.state }}</span>
                  <span class="run-meta">
                    <strong>#{{ run.id }}</strong>
                    <small>{{ run.triggeredBy || '-' }} | {{ formatTime(run.createTime) }}</small>
                  </span>
                  <span class="run-cost">{{ costLine(run) }}</span>
                </button>
              </div>

              <div class="run-detail">
                <template v-if="selectedRun">
                  <div class="detail-head">
                    <div>
                      <h4>#{{ selectedRun.id }}</h4>
                      <p>{{ selectedRun.externalRunId || selectedRun.externalTaskId || '-' }}</p>
                    </div>
                    <span class="run-state" :class="stateClass(selectedRun.state)">{{ selectedRun.state }}</span>
                  </div>
                  <dl class="kv-grid">
                    <div><dt>{{ t('difyWorkflow.detail.triggeredBy') }}</dt><dd>{{ selectedRun.triggeredBy || '-' }}</dd></div>
                    <div><dt>{{ t('difyWorkflow.detail.completedAt') }}</dt><dd>{{ formatTime(selectedRun.completedAt) }}</dd></div>
                    <div><dt>{{ t('difyWorkflow.detail.tokens') }}</dt><dd>{{ selectedRun.totalTokens ?? '-' }}</dd></div>
                    <div><dt>{{ t('difyWorkflow.detail.steps') }}</dt><dd>{{ selectedRun.totalSteps ?? '-' }}</dd></div>
                  </dl>
                  <p v-if="selectedRun.errorMessage" class="run-error">
                    {{ selectedRun.errorCode || 'error' }}: {{ selectedRun.errorMessage }}
                  </p>
                  <details open>
                    <summary>{{ t('difyWorkflow.detail.inputs') }}</summary>
                    <pre>{{ jsonText(selectedRun.requestInputs) }}</pre>
                  </details>
                  <details>
                    <summary>{{ t('difyWorkflow.detail.outputs') }}</summary>
                    <pre>{{ jsonText(selectedRun.responseOutputs) }}</pre>
                  </details>
                  <details>
                    <summary>{{ t('difyWorkflow.detail.raw') }}</summary>
                    <pre>{{ jsonText(selectedRun.responseRaw) }}</pre>
                  </details>
                </template>
                <div v-else class="empty-note">{{ t('difyWorkflow.selectRun') }}</div>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  difyWorkflowApi,
  type DifyWorkflowConfigVO,
  type DifyWorkflowRunVO,
  type SaveDifyWorkflowConfigRequest,
} from '@/api'
import { mcToast } from '@/composables/useMcToast'

const { t } = useI18n()

const loading = ref(true)
const saving = ref(false)
const runsLoading = ref(false)
const testRunning = ref(false)
const manualRunning = ref(false)
const config = ref<DifyWorkflowConfigVO | null>(null)
const runs = ref<DifyWorkflowRunVO[]>([])
const selectedRun = ref<DifyWorkflowRunVO | null>(null)
const runInputsJson = ref('{\n  "query": ""\n}')

const form = reactive({
  name: 'Dify Workflow',
  description: '',
  baseUrl: 'https://api.dify.ai/v1',
  apiKey: '',
  enabled: false,
  inputSchemaJson: '{\n  "query": "string"\n}',
  defaultInputsJson: '{\n  "query": ""\n}',
})

const apiKeyPlaceholder = computed(() =>
  config.value?.apiKeyConfigured
    ? t('difyWorkflow.fields.apiKeyConfigured')
    : t('difyWorkflow.fields.apiKeyPlaceholder')
)

const canRun = computed(() =>
  form.enabled && Boolean(config.value?.apiKeyConfigured || form.apiKey.trim())
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    await Promise.all([loadConfig(), loadRuns()])
  } finally {
    loading.value = false
  }
}

async function loadConfig() {
  try {
    const res: any = await difyWorkflowApi.getConfig()
    applyConfig(res.data as DifyWorkflowConfigVO)
  } catch (e: any) {
    mcToast.error(e?.message || t('difyWorkflow.loadFailed'))
  }
}

async function loadRuns() {
  runsLoading.value = true
  try {
    const res: any = await difyWorkflowApi.runs(50)
    runs.value = (res.data as DifyWorkflowRunVO[]) || []
    if (!selectedRun.value && runs.value.length) selectedRun.value = runs.value[0]
    if (selectedRun.value && !runs.value.find((r) => String(r.id) === String(selectedRun.value?.id))) {
      selectedRun.value = runs.value[0] || null
    }
  } catch (e: any) {
    mcToast.error(e?.message || t('difyWorkflow.runsLoadFailed'))
  } finally {
    runsLoading.value = false
  }
}

function applyConfig(next: DifyWorkflowConfigVO) {
  config.value = next
  form.name = next.name || 'Dify Workflow'
  form.description = next.description || ''
  form.baseUrl = next.baseUrl || 'https://api.dify.ai/v1'
  form.apiKey = ''
  form.enabled = Boolean(next.enabled)
  form.inputSchemaJson = prettyJsonString(next.inputSchemaJson || '{\n  "query": "string"\n}')
  form.defaultInputsJson = prettyJsonString(next.defaultInputsJson || '{\n  "query": ""\n}')
  runInputsJson.value = form.defaultInputsJson
}

async function saveConfig(showToast = true, preserveRunInputs = false) {
  saving.value = true
  const previousRunInputs = runInputsJson.value
  try {
    const inputSchemaJson = normalizeOptionalObjectJson(
      form.inputSchemaJson,
      t('difyWorkflow.fields.inputSchemaJson')
    )
    const defaultInputsJson = normalizeRequiredObjectJson(
      form.defaultInputsJson || '{}',
      t('difyWorkflow.fields.defaultInputsJson')
    )
    const payload: SaveDifyWorkflowConfigRequest = {
      name: form.name,
      description: form.description,
      enabled: form.enabled,
      inputSchemaJson,
      defaultInputsJson,
    }
    if (form.apiKey.trim()) payload.apiKey = form.apiKey.trim()
    const res: any = await difyWorkflowApi.saveConfig(payload)
    applyConfig(res.data as DifyWorkflowConfigVO)
    if (preserveRunInputs) runInputsJson.value = previousRunInputs
    if (showToast) mcToast.success(t('difyWorkflow.saveSuccess'))
  } catch (e: any) {
    if (showToast) mcToast.error(e?.message || t('difyWorkflow.saveFailed'))
    throw e
  } finally {
    saving.value = false
  }
}

async function testRun() {
  testRunning.value = true
  try {
    const inputs = parseObjectJson(runInputsJson.value, t('difyWorkflow.fields.runInputsJson'))
    await ensureRunnableConfigSaved()
    const res: any = await difyWorkflowApi.test({ inputs })
    const run = res.data as DifyWorkflowRunVO
    await afterRun(run)
    notifyRunResult(run, t('difyWorkflow.testSubmitted'))
  } catch (e: any) {
    mcToast.error(e?.message || t('difyWorkflow.runFailed'))
  } finally {
    testRunning.value = false
  }
}

async function manualRun() {
  manualRunning.value = true
  try {
    const inputs = parseObjectJson(runInputsJson.value, t('difyWorkflow.fields.runInputsJson'))
    await ensureRunnableConfigSaved()
    const res: any = await difyWorkflowApi.run({ inputs })
    const run = res.data as DifyWorkflowRunVO
    await afterRun(run)
    notifyRunResult(run, t('difyWorkflow.runSubmitted'))
  } catch (e: any) {
    mcToast.error(e?.message || t('difyWorkflow.runFailed'))
  } finally {
    manualRunning.value = false
  }
}

async function ensureRunnableConfigSaved() {
  await saveConfig(false, true)
}

async function afterRun(run: DifyWorkflowRunVO) {
  const previousRunInputs = runInputsJson.value
  selectedRun.value = run
  await Promise.all([loadConfig(), loadRuns()])
  runInputsJson.value = previousRunInputs
  selectedRun.value = runs.value.find((r) => String(r.id) === String(run.id)) || run
}

function notifyRunResult(run: DifyWorkflowRunVO, successMessage: string) {
  if ((run.state || '').toLowerCase() === 'failed') {
    mcToast.error(run.errorMessage || t('difyWorkflow.runFailed'))
    return
  }
  mcToast.success(successMessage)
}

function parseObjectJson(raw: string, label: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(raw || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error(t('difyWorkflow.jsonObjectRequired', { label }))
    }
    return parsed as Record<string, unknown>
  } catch (e: any) {
    if (e?.message?.includes(label)) throw e
    throw new Error(t('difyWorkflow.invalidJson', { label, msg: e?.message || 'parse error' }))
  }
}

function normalizeRequiredObjectJson(raw: string, label: string): string {
  return JSON.stringify(parseObjectJson(raw, label), null, 2)
}

function normalizeOptionalObjectJson(raw: string, label: string): string {
  if (!raw || !raw.trim()) return ''
  return normalizeRequiredObjectJson(raw, label)
}

function prettyJsonString(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function jsonText(value: unknown): string {
  if (value == null) return '-'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function formatTime(value?: string | null): string {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString()
}

function stateClass(state?: string) {
  const s = (state || '').toLowerCase()
  return {
    'state-ok': s === 'succeeded',
    'state-running': s === 'running' || s === 'paused',
    'state-warn': s === 'partial_succeeded' || s === 'cancelled',
    'state-fail': s === 'failed',
  }
}

function costLine(run: DifyWorkflowRunVO): string {
  const parts: string[] = []
  if (run.totalTokens != null) parts.push(`${run.totalTokens} tok`)
  if (run.elapsedTimeSeconds != null) parts.push(`${run.elapsedTimeSeconds}s`)
  return parts.join(' | ') || '-'
}
</script>

<style scoped>
.settings-section {
  width: 100%;
}

.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--mc-text-primary);
}

.section-desc {
  margin: 6px 0 0;
  font-size: 14px;
  color: var(--mc-text-secondary);
}

.section-actions,
.run-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.risk-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  border: 1px solid rgba(217, 119, 6, 0.38);
  border-radius: 10px;
  background: rgba(217, 119, 6, 0.09);
  color: var(--mc-text-primary);
  font-size: 13px;
  line-height: 1.6;
}

.risk-banner strong {
  flex: 0 0 auto;
  color: #b45309;
}

.dify-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(360px, 0.95fr);
  gap: 16px;
  align-items: start;
}

.settings-card,
.state-panel {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  padding: 18px;
  width: 100%;
}

.state-panel {
  color: var(--mc-text-secondary);
}

.card-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.card-heading h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  color: var(--mc-text-primary);
}

.card-heading p {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--mc-text-secondary);
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
}

.field small,
.toggle-row small {
  font-size: 12px;
  font-weight: 400;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
}

.form-input {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  padding: 9px 10px;
  font-size: 13px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  outline: none;
  min-height: 38px;
}

.form-input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px var(--mc-primary-bg);
}

.form-input[readonly] {
  color: var(--mc-text-secondary);
}

.mono {
  font-family: var(--mc-font-mono, "JetBrains Mono", Consolas, monospace);
}

.textarea {
  resize: vertical;
  line-height: 1.55;
}

.run-inputs {
  min-height: 220px;
}

.toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
}

.toggle-row > span:first-child {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.toggle-switch {
  position: relative;
  display: inline-flex;
  width: 44px;
  height: 24px;
  flex: 0 0 auto;
}

.toggle-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  inset: 0;
  cursor: pointer;
  background: var(--mc-border);
  border-radius: 999px;
  transition: 0.2s;
}

.toggle-slider::before {
  content: '';
  position: absolute;
  width: 18px;
  height: 18px;
  left: 3px;
  top: 3px;
  background: var(--mc-bg-elevated);
  border-radius: 50%;
  transition: 0.2s;
}

.toggle-switch input:checked + .toggle-slider {
  background: var(--mc-primary);
}

.toggle-switch input:checked + .toggle-slider::before {
  transform: translateX(20px);
}

.status-pill,
.run-state {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}

.status-on,
.state-ok {
  background: rgba(34, 197, 94, 0.12);
  color: #15803d;
}

.status-off {
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
}

.state-running {
  background: rgba(59, 130, 246, 0.12);
  color: #2563eb;
}

.state-warn {
  background: rgba(217, 119, 6, 0.12);
  color: #b45309;
}

.state-fail {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}

.btn-primary,
.btn-secondary {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  padding: 8px 13px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.btn-primary {
  border-color: var(--mc-primary);
  background: var(--mc-primary);
  color: #fff;
}

.btn-secondary {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.last-test {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.last-test__item {
  padding: 10px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
}

.last-test__item span {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

.last-test__item strong {
  font-size: 13px;
  color: var(--mc-text-primary);
}

.last-test__error,
.run-error {
  grid-column: 1 / -1;
  margin: 0;
  padding: 10px;
  border-radius: 8px;
  background: rgba(220, 38, 38, 0.1);
  color: #b91c1c;
  font-size: 12px;
  line-height: 1.5;
}

.runs-panel {
  margin-top: 16px;
}

.empty-note {
  padding: 24px;
  text-align: center;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.runs-layout {
  display: grid;
  grid-template-columns: minmax(320px, 0.8fr) minmax(0, 1.2fr);
  gap: 14px;
}

.runs-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 650px;
  overflow: auto;
}

.run-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 10px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.run-row.active {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.run-meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.run-meta strong,
.run-meta small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.run-meta small,
.run-cost {
  color: var(--mc-text-tertiary);
  font-size: 12px;
}

.run-detail {
  min-width: 0;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  padding: 14px;
}

.detail-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.detail-head h4,
.detail-head p {
  margin: 0;
}

.detail-head p {
  margin-top: 3px;
  color: var(--mc-text-tertiary);
  font-size: 12px;
  word-break: break-all;
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 0 0 12px;
}

.kv-grid div {
  padding: 8px;
  border-radius: 8px;
  background: var(--mc-bg-muted);
}

.kv-grid dt {
  margin-bottom: 3px;
  color: var(--mc-text-tertiary);
  font-size: 11px;
}

.kv-grid dd {
  margin: 0;
  font-size: 12px;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

details {
  border-top: 1px solid var(--mc-border-light);
  padding-top: 10px;
  margin-top: 10px;
}

summary {
  cursor: pointer;
  font-size: 13px;
  font-weight: 650;
}

pre {
  margin: 8px 0 0;
  padding: 12px;
  max-height: 340px;
  overflow: auto;
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  font-family: var(--mc-font-mono, "JetBrains Mono", Consolas, monospace);
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 1120px) {
  .dify-grid,
  .runs-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .section-header,
  .card-heading,
  .detail-head,
  .risk-banner {
    flex-direction: column;
  }

  .section-actions,
  .run-actions {
    width: 100%;
    justify-content: stretch;
  }

  .section-actions button,
  .run-actions button {
    flex: 1;
  }

  .last-test,
  .kv-grid {
    grid-template-columns: 1fr;
  }

  .run-row {
    grid-template-columns: 1fr;
  }
}
</style>
