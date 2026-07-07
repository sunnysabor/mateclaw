<template>
  <div class="billing-page">
    <div class="billing-header">
      <div class="billing-lead">
        <div class="page-kicker">{{ t('billing.kicker') }}</div>
        <h1 class="page-title">{{ t('billing.title') }}</h1>
        <p class="page-desc">{{ t('billing.desc') }}</p>
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
      <section class="mock-notice">
        <div class="notice-icon">ⓘ</div>
        <div>
          <h2>{{ t('billing.mockNoticeTitle') }}</h2>
          <p>{{ t('billing.mockNoticeDesc') }}</p>
        </div>
      </section>

      <section class="summary-grid">
        <div class="balance-card">
          <div class="card-kicker">{{ t('billing.availableBalance') }}</div>
          <div class="balance-value">{{ formatMoney(summary?.wallet?.balanceCents || 0, summary?.wallet?.currency) }}</div>
          <div class="balance-subtitle">{{ t('billing.walletSubtitle') }}</div>
        </div>
        <div class="mini-card">
          <span>{{ t('billing.pendingOrders') }}</span>
          <strong>{{ pendingOrders.length }}</strong>
        </div>
        <div class="mini-card">
          <span>{{ t('billing.recentCredits') }}</span>
          <strong>{{ creditLedger.length }}</strong>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>{{ t('billing.packages') }}</h2>
            <p>{{ t('billing.packagesDesc') }}</p>
          </div>
        </div>
        <div v-if="packages.length === 0" class="empty-inline">{{ t('billing.noPackages') }}</div>
        <div v-else class="package-grid">
          <article v-for="pkg in packages" :key="pkg.id" class="package-card">
            <div class="package-topline">
              <h3>{{ pkg.name }}</h3>
              <span v-if="centsNumber(pkg.bonusCents) > 0" class="bonus-badge">{{ t('billing.bonus', { amount: formatMoney(pkg.bonusCents, pkg.currency) }) }}</span>
            </div>
            <div class="package-amount">{{ formatMoney(pkg.amountCents, pkg.currency) }}</div>
            <p>{{ pkg.description || t('billing.packageFallbackDesc') }}</p>
            <div class="package-total">
              <span>{{ t('billing.creditTotal') }}</span>
              <strong>{{ formatMoney(packageTotal(pkg), pkg.currency) }}</strong>
            </div>
            <button class="action-btn package-btn" :disabled="actionLoading" @click="createOrder(pkg)">
              {{ t('billing.createOrder') }}
            </button>
          </article>
        </div>
      </section>

      <section class="panel" v-if="pendingOrders.length > 0">
        <div class="panel-head">
          <div>
            <h2>{{ t('billing.pendingOrders') }}</h2>
            <p>{{ t('billing.pendingOrdersDesc') }}</p>
          </div>
        </div>
        <div class="order-list">
          <div v-for="order in pendingOrders" :key="order.id" class="order-row order-row--pending">
            <div>
              <div class="order-title">{{ order.orderNo }}</div>
              <div class="order-meta">{{ formatDate(order.createTime) }} · {{ formatMoney(orderTotal(order), order.currency) }}</div>
            </div>
            <div class="order-actions">
              <button class="action-btn" :disabled="actionLoading" @click="mockPay(order)">{{ t('billing.mockPay') }}</button>
              <button class="action-btn action-btn--ghost" :disabled="actionLoading" @click="cancelOrder(order)">{{ t('common.cancel') }}</button>
            </div>
          </div>
        </div>
      </section>

      <section class="history-grid">
        <div class="panel">
          <div class="panel-head">
            <div>
              <h2>{{ t('billing.orders') }}</h2>
              <p>{{ t('billing.ordersDesc') }}</p>
            </div>
          </div>
          <div v-if="orders.length === 0" class="empty-inline">{{ t('billing.noOrders') }}</div>
          <div v-else class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>{{ t('billing.orderNo') }}</th>
                  <th>{{ t('billing.amount') }}</th>
                  <th>{{ t('billing.status') }}</th>
                  <th>{{ t('billing.createdAt') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="order in orders" :key="order.id">
                  <td><span class="mono">{{ order.orderNo }}</span></td>
                  <td>{{ formatMoney(orderTotal(order), order.currency) }}</td>
                  <td><span class="status-pill" :class="`status-pill--${order.status}`">{{ statusLabel(order.status) }}</span></td>
                  <td>{{ formatDate(order.createTime) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <div>
              <h2>{{ t('billing.ledger') }}</h2>
              <p>{{ t('billing.ledgerDesc') }}</p>
            </div>
          </div>
          <div v-if="ledger.length === 0" class="empty-inline">{{ t('billing.noLedger') }}</div>
          <div v-else class="ledger-list">
            <div v-for="entry in ledger" :key="entry.id" class="ledger-row">
              <div>
                <div class="ledger-title">{{ reasonLabel(entry.reason) }}</div>
                <div class="ledger-meta">{{ entry.remark || '-' }} · {{ formatDate(entry.createTime) }}</div>
              </div>
              <div class="ledger-amount">+{{ formatMoney(entry.amountCents, summary?.wallet?.currency) }}</div>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { billingApi } from '@/api/index'
import { mcToast } from '@/composables/useMcToast'

interface Wallet {
  id: string | number
  userId: string | number
  balanceCents: number | string
  currency: string
}

interface BillingPackage {
  id: string | number
  name: string
  description?: string
  amountCents: number | string
  bonusCents: number | string
  currency: string
}

interface BillingOrder {
  id: string | number
  orderNo: string
  amountCents: number | string
  bonusCents: number | string
  currency: string
  paymentMethod: string
  status: 'pending' | 'paid' | 'cancelled' | string
  createTime: string
}

interface LedgerEntry {
  id: string | number
  amountCents: number | string
  balanceAfterCents: number | string
  reason: string
  remark?: string
  createTime: string
}

interface BillingSummary {
  wallet: Wallet
  packages: BillingPackage[]
  orders: BillingOrder[]
  ledger: LedgerEntry[]
}

const { t, locale } = useI18n()
const loading = ref(false)
const actionLoading = ref(false)
const error = ref<string | null>(null)
const summary = ref<BillingSummary | null>(null)

const packages = computed(() => summary.value?.packages || [])
const orders = computed(() => summary.value?.orders || [])
const ledger = computed(() => summary.value?.ledger || [])
const pendingOrders = computed(() => orders.value.filter((order) => order.status === 'pending'))
const creditLedger = computed(() => ledger.value.filter((entry) => centsNumber(entry.amountCents) > 0))

function currencyCode(currency?: string): string {
  return currency || 'CNY'
}

function centsNumber(cents?: number | string): number {
  const value = Number(cents || 0)
  return Number.isFinite(value) ? value : 0
}

function packageTotal(pkg: Pick<BillingPackage, 'amountCents' | 'bonusCents'>): number {
  return centsNumber(pkg.amountCents) + centsNumber(pkg.bonusCents)
}

function orderTotal(order: Pick<BillingOrder, 'amountCents' | 'bonusCents'>): number {
  return centsNumber(order.amountCents) + centsNumber(order.bonusCents)
}

function formatMoney(cents?: number | string, currency?: string): string {
  const value = centsNumber(cents) / 100
  return new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency: currencyCode(currency),
    minimumFractionDigits: value % 1 === 0 ? 0 : 2,
  }).format(value)
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

function statusLabel(status: string): string {
  return t(`billing.statusMap.${status}`, status)
}

function reasonLabel(reason: string): string {
  return t(`billing.reasonMap.${reason}`, reason)
}

async function loadSummary() {
  loading.value = true
  error.value = null
  try {
    const res: any = await billingApi.getSummary()
    summary.value = res.data
  } catch (e: any) {
    const msg = e?.message || t('billing.loadFailed')
    error.value = msg
    mcToast.error(msg)
  } finally {
    loading.value = false
  }
}

async function createOrder(pkg: BillingPackage) {
  actionLoading.value = true
  try {
    await billingApi.createOrder({ packageId: pkg.id, paymentMethod: 'mock' })
    mcToast.success(t('billing.orderCreated'))
    await loadSummary()
  } catch (e: any) {
    mcToast.error(e?.message || t('billing.orderCreateFailed'))
  } finally {
    actionLoading.value = false
  }
}

async function mockPay(order: BillingOrder) {
  actionLoading.value = true
  try {
    await billingApi.mockPay(order.id)
    mcToast.success(t('billing.paySuccess'))
    await loadSummary()
  } catch (e: any) {
    mcToast.error(e?.message || t('billing.payFailed'))
  } finally {
    actionLoading.value = false
  }
}

async function cancelOrder(order: BillingOrder) {
  actionLoading.value = true
  try {
    await billingApi.cancelOrder(order.id)
    mcToast.success(t('billing.orderCancelled'))
    await loadSummary()
  } catch (e: any) {
    mcToast.error(e?.message || t('billing.cancelFailed'))
  } finally {
    actionLoading.value = false
  }
}

onMounted(loadSummary)
</script>

<style scoped>
.billing-page {
  min-height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 24px;
  color: var(--mc-text-primary);
}

.billing-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.billing-lead {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.page-kicker {
  display: inline-flex;
  width: fit-content;
  padding: 6px 12px;
  border: 1px solid color-mix(in srgb, var(--mc-primary) 18%, transparent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--mc-primary-bg) 72%, var(--mc-bg-elevated) 28%);
  color: var(--mc-primary-hover);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.page-title {
  margin: 0;
  font-size: clamp(28px, 4vw, 40px);
  line-height: 0.95;
  font-weight: 800;
}

.page-desc {
  max-width: 680px;
  margin: 0;
  color: var(--mc-text-secondary);
  line-height: 1.55;
}

.mock-notice,
.panel,
.balance-card,
.mini-card {
  border: 1px solid var(--mc-border);
  border-radius: 22px;
  background: color-mix(in srgb, var(--mc-bg-elevated) 86%, transparent);
  box-shadow: var(--mc-shadow-sm);
}

.mock-notice {
  display: flex;
  gap: 14px;
  padding: 16px 18px;
  border-color: color-mix(in srgb, var(--mc-warning, #f59e0b) 28%, var(--mc-border));
  background: color-mix(in srgb, #f59e0b 8%, var(--mc-bg-elevated));
}

.notice-icon {
  flex: 0 0 auto;
  color: #b45309;
  font-weight: 800;
}

.mock-notice h2 {
  margin: 0 0 4px;
  font-size: 15px;
}

.mock-notice p {
  margin: 0;
  color: var(--mc-text-secondary);
  line-height: 1.5;
}

.summary-grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) repeat(2, minmax(150px, 220px));
  gap: 14px;
}

.balance-card {
  padding: 24px;
  background: radial-gradient(circle at top right, color-mix(in srgb, var(--mc-primary) 18%, transparent), transparent 42%), var(--mc-bg-elevated);
}

.card-kicker,
.mini-card span {
  color: var(--mc-text-tertiary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.balance-value {
  margin-top: 10px;
  font-size: clamp(34px, 5vw, 54px);
  font-weight: 850;
  line-height: 1;
}

.balance-subtitle {
  margin-top: 10px;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.mini-card {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 18px;
}

.mini-card strong {
  font-size: 34px;
  line-height: 1;
}

.panel {
  padding: 20px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-head h2 {
  margin: 0;
  font-size: 18px;
}

.panel-head p {
  margin: 6px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.package-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}

.package-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--mc-border);
  border-radius: 18px;
  background: color-mix(in srgb, var(--mc-bg) 42%, var(--mc-bg-elevated));
}

.package-topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.package-topline h3 {
  margin: 0;
  font-size: 17px;
}

.bonus-badge {
  padding: 4px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, #22c55e 14%, transparent);
  color: #16a34a;
  font-size: 12px;
  font-weight: 700;
}

.package-amount {
  font-size: 30px;
  font-weight: 850;
}

.package-card p {
  min-height: 38px;
  margin: 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
  line-height: 1.45;
}

.package-total {
  display: flex;
  justify-content: space-between;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.package-total strong {
  color: var(--mc-text-primary);
}

.package-btn {
  width: 100%;
  justify-content: center;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 34px;
  padding: 8px 12px;
  border: 1px solid color-mix(in srgb, var(--mc-primary) 34%, var(--mc-border));
  border-radius: 12px;
  background: var(--mc-primary);
  color: var(--mc-primary-contrast, #fff);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.15s ease, opacity 0.15s ease;
}

.action-btn:hover:not(:disabled) { transform: translateY(-1px); }
.action-btn:disabled { cursor: not-allowed; opacity: 0.55; }

.action-btn--ghost {
  background: transparent;
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}

.order-list,
.ledger-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.order-row,
.ledger-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  background: var(--mc-bg);
}

.order-row--pending {
  border-color: color-mix(in srgb, var(--mc-primary) 22%, var(--mc-border));
}

.order-title,
.ledger-title {
  font-weight: 750;
}

.order-meta,
.ledger-meta {
  margin-top: 4px;
  color: var(--mc-text-tertiary);
  font-size: 12px;
}

.order-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.history-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(300px, 0.75fr);
  gap: 14px;
}

.table-wrap {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th,
.data-table td {
  padding: 11px 10px;
  border-bottom: 1px solid var(--mc-border);
  text-align: left;
  white-space: nowrap;
}

.data-table th {
  color: var(--mc-text-tertiary);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.status-pill {
  display: inline-flex;
  padding: 4px 8px;
  border-radius: 999px;
  background: var(--mc-bg-muted, rgba(148, 163, 184, 0.14));
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.status-pill--paid {
  background: color-mix(in srgb, #22c55e 14%, transparent);
  color: #16a34a;
}

.status-pill--pending {
  background: color-mix(in srgb, #f59e0b 16%, transparent);
  color: #b45309;
}

.status-pill--cancelled {
  background: color-mix(in srgb, #94a3b8 16%, transparent);
  color: var(--mc-text-tertiary);
}

.ledger-amount {
  color: #16a34a;
  font-weight: 850;
  white-space: nowrap;
}

.empty-inline,
.empty-state,
.loading-state {
  color: var(--mc-text-tertiary);
  text-align: center;
}

.empty-inline {
  padding: 24px;
  border: 1px dashed var(--mc-border);
  border-radius: 14px;
}

.empty-state,
.loading-state {
  display: flex;
  min-height: 260px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.empty-icon { font-size: 32px; }

.spinner {
  width: 28px;
  height: 28px;
  border: 3px solid var(--mc-border);
  border-top-color: var(--mc-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 980px) {
  .summary-grid,
  .history-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .billing-page { padding: 16px; }
  .order-row,
  .ledger-row {
    align-items: stretch;
    flex-direction: column;
  }
  .order-actions { justify-content: flex-start; }
}
</style>
