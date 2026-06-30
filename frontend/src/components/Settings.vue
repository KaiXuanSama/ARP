<script setup lang="ts">
import { computed, h, onMounted, ref, type VNode } from 'vue'
import { NSelect, NSpin, useMessage, type SelectOption, type SelectGroupOption } from 'naive-ui'
import Icon from './Icon.vue'
import {
  getAccountExtra,
  getCachedAccounts,
  type CachedAccount,
  type CreditSnapshot,
} from '../utils/accountExtras'

/**
 * 系统设置页面（仅前端）
 * <p>
 * 当前只暴露"消耗方式"下拉框,用于给后续的对话模式 / 多账户路由选号策略。
 * - 指定模式 → 额外显示"指定账号"下拉框(账号列表从 GET /api/accounts 拉)
 * - 其他三种 → 隐藏指定账号下拉
 * <p>
 * 不做持久化:选择存在 reactive 内存里,刷新页面会丢。后端 / localStorage 留到下一步接。
 */

// ===== 消耗方式枚举 =====

/** 消耗方式选项的 value 常量,跟后端契约对齐(预留) */
const MODE = {
  DESIGNATED: 'designated', // 指定模式
  LEAST: 'least',           // 量少优先
  EXPIRING: 'expiring',     // 临期优先
  MOST: 'most',             // 量多优先
} as const

type ConsumptionMode = typeof MODE[keyof typeof MODE]

const modeOptions: SelectOption[] = [
  { value: MODE.DESIGNATED, label: '指定模式' },
  { value: MODE.LEAST,      label: '量少优先' },
  { value: MODE.EXPIRING,   label: '临期优先' },
  { value: MODE.MOST,       label: '量多优先' },
]

const consumptionMode = ref<ConsumptionMode>(MODE.DESIGNATED)

/**
 * 当前 mode 是否需要展示"指定账号"下拉框
 * <p>
 * 命名对应后端预留语义:designated 表示人工指定,其余三种由后端自动选号
 */
const showAccountPicker = computed(() => consumptionMode.value === MODE.DESIGNATED)

// ===== 账号列表(从 localStorage 读,不发请求) =====

/**
 * 下拉框里展示用的账号条目(在 CachedAccount 基础上补充 credit 快照)
 * <p>
 * 跟 Naive UI 的 SelectOption 同形(value/label),多出来的 credit 字段只用于 render-label 渲染
 */
interface AccountOption extends SelectOption {
  id: number
  uid: string
  nickname: string
  credit: CreditSnapshot | null
}

const message = useMessage()
const accountOptions = ref<AccountOption[]>([])
const selectedAccountId = ref<number | null>(null)
const loadingAccounts = ref(false)

/**
 * 把 CreditSnapshot 折叠成"已用/总量"两个数
 * <p>
 * 算法与 AccountManagement.vue 表格"积分剩余"列完全一致:
 *   remain = Σ packages.cycleCapacityRemain
 *   size   = Σ packages.cycleCapacitySize
 */
function summarizeCredit(snap: CreditSnapshot | null | undefined): { remain: number; size: number } {
  if (!snap || !Array.isArray(snap.packages)) return { remain: 0, size: 0 }
  let remain = 0
  let size = 0
  for (const p of snap.packages) {
    remain += p.cycleCapacityRemain || 0
    size += p.cycleCapacitySize || 0
  }
  return { remain, size }
}

function displayName(rec: CachedAccount): string {
  if (rec.nickname && rec.nickname !== '未定义') return rec.nickname
  return rec.uid
}

function buildAccountOption(rec: CachedAccount): AccountOption {
  const extra = getAccountExtra(rec.uid)
  return {
    value: rec.id,
    label: displayName(rec), // 兜底:render-label 失败时 Naive 会用 label
    id: rec.id,
    uid: rec.uid,
    nickname: displayName(rec),
    credit: extra?.credit ?? null,
  }
}

/**
 * 同步从 localStorage 读账号列表(由 AccountManagement 写入,见 utils/accountExtras.ts)
 * <p>
 * Settings 是只读场景,不发请求;空列表提示用户去账号管理先走一遍
 */
function loadAccounts(): void {
  loadingAccounts.value = true
  try {
    const records = getCachedAccounts()
    accountOptions.value = records.map(buildAccountOption)
    // 默认选中第一项(仅在用户没选过时)
    if (selectedAccountId.value == null && accountOptions.value.length > 0) {
      selectedAccountId.value = accountOptions.value[0].id
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(msg)
    accountOptions.value = []
  } finally {
    loadingAccounts.value = false
  }
}

/**
 * Naive UI render-label 渲染函数
 * <p>
 * 显示格式: 昵称（credit.remain / credit.size）
 * <p>
 * 没昵称时退化为 uid;没 credit 快照时括号内显示 "-"
 * <p>
 * 例: "凯旋Sama（3067.6 / 3100）" / "manual-abc…（-）"
 */
function renderAccountLabel(option: SelectOption | SelectGroupOption): VNode {
  const opt = option as AccountOption
  const { remain, size } = summarizeCredit(opt.credit)
  const creditText = opt.credit
    ? `${remain.toFixed(1)} / ${size.toFixed(0)}`
    : '-'
  const text = `${opt.nickname || opt.uid}（${creditText}）`
  return h('div', { class: 'account-option' }, text)
}

onMounted(() => {
  loadAccounts()
})
</script>

<template>
  <div class="settings">
    <!-- 对话模式相关 -->
    <div class="card">
      <div class="card-header">
        <h3 class="card-title">对话模式</h3>
        <p class="card-desc">控制对话请求命中账号的策略。后续接 Chat / Billing 路由时使用。</p>
      </div>
      <div class="card-body">
        <div class="form-row">
          <label class="form-label" for="consumption-mode">消耗方式</label>
          <div class="form-control">
            <n-select
              id="consumption-mode"
              v-model:value="consumptionMode"
              :options="modeOptions"
              placeholder="请选择消耗方式"
            />
          </div>
        </div>

        <div v-if="showAccountPicker" class="form-row">
          <label class="form-label" for="designated-account">指定账号</label>
          <div class="form-control">
            <n-spin :show="loadingAccounts" size="small">
              <n-select
                id="designated-account"
                v-model:value="selectedAccountId"
                :options="accountOptions"
                :render-label="renderAccountLabel"
                placeholder="请选择账号"
                :disabled="accountOptions.length === 0"
                clearable
              />
            </n-spin>
            <p v-if="!loadingAccounts && accountOptions.length === 0" class="form-hint">
              暂无可用账号,请先到
              <router-link to="/accounts">账号管理</router-link>
              添加。
            </p>
          </div>
        </div>

        <div v-else class="form-row form-row-hint">
          <div class="form-label"></div>
          <div class="form-control form-control-hint">
            <Icon name="settings" :size="14" />
            <span>当前模式由后端自动选择账号,无需手动指定。</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 720px;
}

.card {
  background: #ffffff;
  border: 1px solid #ececf0;
  border-radius: 8px;
}

.card-header {
  padding: 16px 20px;
  border-bottom: 1px solid #ececf0;
}

.card-title {
  margin: 0 0 4px 0;
  font-size: 15px;
  font-weight: 600;
  color: #1f2329;
}

.card-desc {
  margin: 0;
  font-size: 12px;
  color: #8c8c8c;
  line-height: 1.5;
}

.card-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-row {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.form-label {
  flex: 0 0 100px;
  font-size: 13px;
  color: #4e5969;
  padding-top: 6px;
  font-weight: 500;
}

.form-control {
  flex: 1;
  min-width: 0;
}

.form-control-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #8c8c8c;
  font-size: 12px;
  padding-top: 6px;
}

.form-hint {
  margin: 6px 0 0 0;
  font-size: 12px;
  color: #8c8c8c;
}

.form-hint a {
  color: #18a058;
  text-decoration: none;
}

.form-hint a:hover {
  text-decoration: underline;
}

.form-row-hint .form-control-hint :deep(svg) {
  flex-shrink: 0;
}

/* ========== 指定账号下拉项：昵称（credit）单行格式 ========== */
:deep(.account-option) {
  display: block;
  font-size: 13px;
  color: #1f2329;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
