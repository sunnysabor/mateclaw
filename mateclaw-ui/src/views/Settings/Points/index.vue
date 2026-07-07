<template>
  <div class="points-page">
    <div class="points-header">
      <div>
        <div class="page-kicker">{{ t('points.kicker') }}</div>
        <h1 class="page-title">{{ t('points.title') }}</h1>
        <p class="page-desc">{{ t('points.desc') }}</p>
      </div>
      <button class="action-btn action-btn--ghost" :disabled="loading" @click="loadSummary">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="23 4 23 10 17 10" />
          <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
        </svg>
        {{ t('common.refresh') }}
      </button>
    </div>

    <div v-if="loading && !summary" class="loading-state">
      <div class="spinner"></div>
      <p>{{ t('common.loading') }}</p>
    </div>

    <div v-else-if="error" class="empty-state">
      <span class="empty-icon">⚠️</span>
      <p>{{ error }}</p>
      <button class="action-btn" @click="loadSummary">{{ t('common.refresh') }}</button>
    </div>

    <template v-else>
      <section class="rules-card">
        <div class="rules-icon">✦</div>
        <div>
          <h2>{{ t('points.rulesTitle') }}</h2>
          <p>{{ t('points.rulesDesc') }}</p>
        </div>
      </section>

      <section class="summary-grid">
        <div class="balance-card">
          <div class="card-kicker">{{ t('points.availablePoints') }}</div>
          <div class="points-value">{{ formatPoints(account?.balance) }}</div>
          <div class="balance-subtitle">{{ t('points.level') }}：{{ levelLabel(account?.levelCode) }}</div>
        </div>
        <div class="mini-card mini-card--earn">
          <span>{{ t('points.totalEarned') }}</span>
          <strong>{{ formatPoints(account?.totalEarned) }}</strong>
        </div>
        <div class="mini-card mini-card--spent">
          <span>{{ t('points.totalSpent') }}</span>
          <strong>{{ formatPoints(account?.totalSpent) }}</strong>
        </div>
      </section>

      <section v-if="isAdmin" class="panel admin-panel">
        <div class="panel-head">
          <div>
            <h2>{{ t('points.adminTitle') }}</h2>
            <p>{{ t('points.adminDesc') }}</p>
          </div>
        </div>
        <form class="adjust-form" @submit.prevent="submitManualAdjust">
          <label>
            <span>{{ t('points.targetUser') }}</span>
            <select v-model="adjustForm.userId" :disabled="usersLoading || actionLoading">
              <option value="">{{ t('points.selectUser') }}</option>
              <option v-for="user in users" :key="user.id" :value="String(user.id)">
                {{ user.nickname || user.username }} ({{ user.username }})
              </option>
            </select>
          </label>
          <label>
            <span>{{ t('points.adjustAmount') }}</span>
            <input
              v-model="adjustForm.amount"
              type="number"
              step="1"
              :placeholder="t('points.adjustAmountHint')"
              :disabled="actionLoading"
            />
          </label>
          <label>
            <span>{{ t('points.adjustReason') }}</span>
            <input v-model="adjustForm.reason" type="text" :disabled="actionLoading" />
          </label>
          <label class="form-wide">
            <span>{{ t('points.adjustRemark') }}</span>
            <input v-model="adjustForm.remark" type="text" :disabled="actionLoading" />
          </label>
          <button class="action-btn" type="submit" :disabled="actionLoading">
            {{ t('points.submitAdjust') }}
          </button>
        </form>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>{{ t('points.ledger') }}</h2>
            <p>{{ t('points.ledgerDesc') }}</p>
          </div>
        </div>
        <div v-if="ledger.length === 0" class="empty-inline">{{ t('points.noLedger') }}</div>
        <div v-else class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>{{ t('points.reason') }}</th>
                <th>{{ t('points.amount') }}</th>
                <th>{{ t('points.balanceAfter') }}</th>
                <th>{{ t('points.remark') }}</th>
                <th>{{ t('points.createdAt') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in ledger" :key="entry.id">
                <td>
                  <span class="reason-title">{{ reasonLabel(entry.reason) }}</span>
                  <span class="direction-pill" :class="`direction-pill--${entry.direction}`">
                    {{ directionLabel(entry.direction) }}
                  </span>
                </td>
                <td :class="['amount-cell', amountNumber(entry.amount) >= 0 ? 'is-credit' : 'is-debit']">
                  {{ signedPoints(entry.amount) }}
                </td>
                <td>{{ formatPoints(entry.balanceAfter) }}</td>
                <td>{{ entry.remark || '-' }}</td>
                <td>{{ formatDate(entry.createTime) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { authApi, pointsApi } from '@/api/index'
import { mcToast } from '@/composables/useMcToast'

interface PointsAccount {
  id: string | number
  userId: string | number
  balance: number | string
  totalEarned: number | string
  totalSpent: number | string
  levelCode: string
}

interface PointsLedgerEntry {
  id: string | number
  direction: 'credit' | 'debit' | 'adjust' | string
  amount: number | string
  balanceAfter: number | string
  reason: string
  remark?: string
  createTime: string
}

interface PointsSummary {
  account: PointsAccount
  ledger: PointsLedgerEntry[]
}

interface UserOption {
  id: string | number
  username: string
  nickname?: string
}

const { t, locale } = useI18n()
const loading = ref(false)
const actionLoading = ref(false)
const usersLoading = ref(false)
const error = ref<string | null>(null)
const summary = ref<PointsSummary | null>(null)
const users = ref<UserOption[]>([])

const isAdmin = computed(() => localStorage.getItem('role') === 'admin')
const account = computed(() => summary.value?.account)
const ledger = computed(() => summary.value?.ledger || [])

const adjustForm = reactive({
  userId: '',
  amount: '',
  reason: 'admin_adjustment',
  remark: '',
})

function amountNumber(value?: number | string): number {
  const numberValue = Number(value || 0)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function formatPoints(value?: number | string): string {
  return new Intl.NumberFormat(locale.value, { maximumFractionDigits: 0 }).format(amountNumber(value))
}

function signedPoints(value?: number | string): string {
  const amount = amountNumber(value)
  const formatted = formatPoints(Math.abs(amount))
  return `${amount >= 0 ? '+' : '-'}${formatted}`
}

function formatDate(value?: string): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(locale.value, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function levelLabel(levelCode?: string): string {
  return t(`points.levelMap.${levelCode || 'normal'}`, levelCode || t('points.levelNormal'))
}

function reasonLabel(reason: string): string {
  return t(`points.reasonMap.${reason}`, reason)
}

function directionLabel(direction: string): string {
  return t(`points.directionMap.${direction}`, direction)
}

async function loadSummary() {
  loading.value = true
  error.value = null
  try {
    const res: any = await pointsApi.getSummary({ limit: 50 })
    summary.value = res.data
  } catch (e: any) {
    const msg = e?.message || t('points.loadFailed')
    error.value = msg
    mcToast.error(msg)
  } finally {
    loading.value = false
  }
}

async function loadUsers() {
  if (!isAdmin.value) return
  usersLoading.value = true
  try {
    const res: any = await authApi.listUsers()
    users.value = res.data || []
  } catch (e: any) {
    mcToast.error(e?.message || t('points.loadUsersFailed'))
  } finally {
    usersLoading.value = false
  }
}

async function submitManualAdjust() {
  const amount = Number(adjustForm.amount)
  if (!adjustForm.userId || !Number.isInteger(amount) || amount === 0) {
    mcToast.error(t('points.adjustInvalid'))
    return
  }
  actionLoading.value = true
  try {
    await pointsApi.manualAdjust({
      userId: adjustForm.userId,
      amount,
      reason: adjustForm.reason || 'admin_adjustment',
      remark: adjustForm.remark || undefined,
    })
    mcToast.success(t('points.adjustSuccess'))
    adjustForm.amount = ''
    adjustForm.remark = ''
    await loadSummary()
  } catch (e: any) {
    mcToast.error(e?.message || t('points.adjustFailed'))
  } finally {
    actionLoading.value = false
  }
}

onMounted(async () => {
  await loadSummary()
  await loadUsers()
})
</script>

<style scoped>
.points-page {
  min-height: 100%;
  padding: 32px;
  color: var(--mc-text, #1f2937);
}

.points-header {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-start;
  margin-bottom: 24px;
}

.page-kicker {
  color: #7c3aed;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  margin-bottom: 8px;
}

.page-title {
  font-size: 32px;
  line-height: 1.2;
  margin: 0 0 10px;
}

.page-desc {
  margin: 0;
  max-width: 760px;
  color: var(--mc-text-secondary, #6b7280);
}

.action-btn {
  border: 0;
  border-radius: 12px;
  padding: 10px 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  background: linear-gradient(135deg, #7c3aed, #2563eb);
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.16s ease, box-shadow 0.16s ease, opacity 0.16s ease;
  box-shadow: 0 12px 24px rgba(124, 58, 237, 0.22);
}

.action-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 16px 30px rgba(124, 58, 237, 0.28);
}

.action-btn:disabled {
  cursor: not-allowed;
  opacity: 0.62;
}

.action-btn--ghost {
  background: rgba(124, 58, 237, 0.08);
  color: #6d28d9;
  box-shadow: none;
  border: 1px solid rgba(124, 58, 237, 0.18);
}

.loading-state,
.empty-state {
  min-height: 280px;
  display: grid;
  place-items: center;
  text-align: center;
  color: var(--mc-text-secondary, #6b7280);
}

.spinner {
  width: 28px;
  height: 28px;
  border: 3px solid rgba(124, 58, 237, 0.18);
  border-top-color: #7c3aed;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.empty-icon {
  font-size: 36px;
}

.rules-card,
.panel,
.balance-card,
.mini-card {
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 20px;
  background: var(--mc-card-bg, rgba(255, 255, 255, 0.92));
  box-shadow: 0 14px 40px rgba(15, 23, 42, 0.06);
}

.rules-card {
  display: flex;
  gap: 16px;
  padding: 18px 20px;
  margin-bottom: 20px;
  background: linear-gradient(135deg, rgba(124, 58, 237, 0.12), rgba(37, 99, 235, 0.08));
}

.rules-icon {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #7c3aed, #2563eb);
  flex: 0 0 auto;
}

.rules-card h2,
.panel h2 {
  margin: 0 0 6px;
  font-size: 18px;
}

.rules-card p,
.panel p {
  margin: 0;
  color: var(--mc-text-secondary, #6b7280);
}

.summary-grid {
  display: grid;
  grid-template-columns: minmax(280px, 1.4fr) repeat(2, minmax(180px, 0.8fr));
  gap: 18px;
  margin-bottom: 18px;
}

.balance-card {
  padding: 24px;
  background: radial-gradient(circle at top right, rgba(124, 58, 237, 0.18), transparent 34%), var(--mc-card-bg, #fff);
}

.card-kicker,
.mini-card span {
  color: var(--mc-text-secondary, #6b7280);
  font-size: 13px;
  font-weight: 700;
}

.points-value {
  font-size: 44px;
  line-height: 1.1;
  font-weight: 800;
  margin: 12px 0 8px;
  color: #6d28d9;
}

.balance-subtitle {
  color: var(--mc-text-secondary, #6b7280);
}

.mini-card {
  padding: 22px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 130px;
}

.mini-card strong {
  font-size: 30px;
}

.mini-card--earn strong {
  color: #059669;
}

.mini-card--spent strong {
  color: #dc2626;
}

.panel {
  padding: 22px;
  margin-top: 18px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 16px;
}

.adjust-form {
  display: grid;
  grid-template-columns: repeat(4, minmax(160px, 1fr)) auto;
  gap: 12px;
  align-items: end;
}

.adjust-form label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary, #6b7280);
  font-size: 13px;
  font-weight: 700;
}

.adjust-form input,
.adjust-form select {
  min-height: 40px;
  border: 1px solid rgba(148, 163, 184, 0.32);
  border-radius: 12px;
  padding: 0 12px;
  color: var(--mc-text, #1f2937);
  background: var(--mc-input-bg, #fff);
}

.table-wrap {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 760px;
}

.data-table th,
.data-table td {
  padding: 13px 12px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  text-align: left;
  vertical-align: middle;
}

.data-table th {
  color: var(--mc-text-secondary, #6b7280);
  font-size: 12px;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.reason-title {
  font-weight: 700;
  margin-right: 8px;
}

.direction-pill {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 700;
  color: #475569;
  background: rgba(100, 116, 139, 0.12);
}

.direction-pill--credit {
  color: #047857;
  background: rgba(16, 185, 129, 0.12);
}

.direction-pill--debit {
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.12);
}

.direction-pill--adjust {
  color: #6d28d9;
  background: rgba(124, 58, 237, 0.12);
}

.amount-cell {
  font-weight: 800;
}

.amount-cell.is-credit {
  color: #059669;
}

.amount-cell.is-debit {
  color: #dc2626;
}

.empty-inline {
  padding: 28px;
  text-align: center;
  color: var(--mc-text-secondary, #6b7280);
  background: rgba(148, 163, 184, 0.08);
  border-radius: 16px;
}

@media (max-width: 1100px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .adjust-form {
    grid-template-columns: repeat(2, minmax(180px, 1fr));
  }
}

@media (max-width: 720px) {
  .points-page {
    padding: 20px;
  }

  .points-header,
  .rules-card {
    flex-direction: column;
  }

  .adjust-form {
    grid-template-columns: 1fr;
  }
}
</style>
