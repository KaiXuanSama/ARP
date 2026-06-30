<script setup lang="ts">
import { computed, h, onMounted, ref, type VNode } from 'vue'
import { NSelect, NSpin, useMessage, type SelectOption, type SelectGroupOption } from 'naive-ui'
import Icon from './Icon.vue'
import type { CreditSnapshot } from '../utils/accountExtras'
import { useAccountData } from '../composables/useAccountData'

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

// ===== 账号列表（走 store，缓存空时 store 内部会阻塞拉服务端一次） =====

/**
 * 下拉框里展示用的账号条目（在 store 提供的 AccountDataView 基础上补充 credit 快照）
 * <p>
 * 跟 Naive UI 的 SelectOption 同形（value/label），多出来的 credit 字段只用于 render-label 渲染
 */
interface AccountOption extends SelectOption {
  id: number
  uid: string
  nickname: string
  credit: CreditSnapshot | null
}

const message = useMessage()
const accountStore = useAccountData()
const selectedAccountId = ref<number | null>(null)
const loadingAccounts = ref(false)

/**
 * 把 store 的扁平视图转成下拉框用的 AccountOption
 * <p>
 * store.view 已经把 credit 拼好了,这里只负责 SelectOption 的 value/label 兜底
 */
const accountOptions = computed<AccountOption[]>(() =>
  accountStore.view
    .filter((v) => v.uid && v.uid !== '-')
    .map((v) => ({
      value: v.id,
      label: v.nickname && v.nickname !== '未定义' ? v.nickname : v.uid,
      id: v.id,
      uid: v.uid,
      nickname: v.nickname && v.nickname !== '未定义' ? v.nickname : v.uid,
      credit: v.credit,
    })),
)

/**
 * 触发 store 加载(缓存非空立刻返回;缓存空阻塞拉一次服务端)
 * <p>
 * onMounted 调一次即可,数据回流到 accountStore.view → accountOptions 自动重算
 * <p>
 * 串行等 ensureExtrasLoaded:让下拉项右侧的"积分消耗"也能显示出来,
 * 否则 credit 字段为 null,渲染出来全是 "-"
 */
async function loadAccounts(): Promise<void> {
  loadingAccounts.value = true
  try {
    await accountStore.ensureAccountsLoaded()
    // 默认选中第一项(仅在用户没选过时)
    if (selectedAccountId.value == null && accountOptions.value.length > 0) {
      selectedAccountId.value = accountOptions.value[0].id
    }
    // 后台拉所有账号的 credit/usage;accountOptions 是 computed,
    // store 写完 localStorage 后会触发它重算,下拉项右侧自动刷新
    await accountStore.ensureExtrasLoaded()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(msg)
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
