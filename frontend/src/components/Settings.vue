<script setup lang="ts">
import { computed, h, onMounted, ref, watch, type VNode } from 'vue'
import { NSelect, NSpin, NButton, useMessage, type SelectOption, type SelectGroupOption } from 'naive-ui'
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
  { value: MODE.EXPIRING,   label: '临期优先' },
  { value: MODE.LEAST,      label: '量少优先' },
  { value: MODE.MOST,       label: '量大优先' },
]

const consumptionMode = ref<ConsumptionMode>(MODE.DESIGNATED)

/**
 * 当前 mode 是否需要展示"指定账号"下拉框
 * <p>
 * designated —— 编辑型下拉,用户可选<br>
 * 非 designated —— 只读型预览,展示后端在当前 mode 下会选哪个账号
 */
const showAccountPicker = computed(() => consumptionMode.value === MODE.DESIGNATED)
const showPreviewAccount = computed(() => consumptionMode.value !== MODE.DESIGNATED)

/** 后端 preview 接口返回的"非指定模式"下当前会命中的账号信息 */
interface PreviewChatAccount {
  accountId: number
  uid: string
  mode: string
  packageCode: string | null
  cycleEndTime: string | null
  cycleCapacityRemain: number | null
}

/** 非指定模式下的预览账号(只用于显示,不参与表单提交) */
const previewAccount = ref<PreviewChatAccount | null>(null)
const previewError = ref<string | null>(null)
const loadingPreview = ref(false)

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
 * 预览模式下"指定账号"下拉框的可选项
 * <p>
 * 只有一项:后端在当前 mode 下选中的账号(若有);无时是空数组 → 下拉显示 placeholder
 * <p>
 * label 拼上 uid 用于"如果 store 里没缓存该账号昵称"时的兜底显示
 */
const previewOptions = computed<AccountOption[]>(() => {
  const p = previewAccount.value
  if (!p) return []
  // 尽量从 store 拿昵称,让用户看到熟悉的显示;拿不到就退到 uid
  const cached = accountStore.view.find((v) => v.id === p.accountId)
  const nickname = cached?.nickname && cached.nickname !== '未定义' ? cached.nickname : p.uid
  return [{
    value: p.accountId,
    label: nickname,
    id: p.accountId,
    uid: p.uid,
    nickname,
    credit: cached?.credit ?? null,
  }]
})

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
 * 预览模式下的下拉项 label 渲染
 * <p>
 * 在 "指定模式" 一样的 `昵称(余量/总量)` 基础上,后面追加"选中原因",让用户看到
 * 为什么后端会选这个账号:
 * <ul>
 *   <li>临期优先 → 追加 `· 到期时间`</li>
 *   <li>量少优先 / 量大优先 → 不追加额外原因文案,仅显示账号与当前积分余量</li>
 * </ul>
 * <p>
 * 渲染示例:
 * <ul>
 *   <li>临期优先: "凯旋Sama（3200.0 / 3200） · 2026-07-10 23:59:59"</li>
 *   <li>量少优先: "凯旋Sama（3200.0 / 3200）"</li>
 * </ul>
 */
function renderPreviewLabel(option: SelectOption | SelectGroupOption): VNode {
  const opt = option as AccountOption
  const { remain, size } = summarizeCredit(opt.credit)
  const creditText = opt.credit
    ? `${remain.toFixed(1)} / ${size.toFixed(0)}`
    : '-'
  const base = `${opt.nickname || opt.uid}（${creditText}）`
  // 预览模式:临期优先追加到期时间;量少/量大优先不追加额外文案。
  // packageCode 不显示 —— "TCACA_code_007_nzdH5h4Nl0" 这类内部 code 对用户无意义
  const p = previewAccount.value
  const reason = p?.mode === MODE.EXPIRING && p.cycleEndTime ? ` · ${p.cycleEndTime}` : ''
  return h('div', { class: 'account-option account-option-preview' }, `${base}${reason}`)
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

/**
 * 拉取"非指定模式"下后端会选中的账号预览
 * <p>
 * 走 GET /api/settings/chat.consumption/preview?mode=...
 * <ul>
 *   <li>成功 → 写入 previewAccount,清空 previewError</li>
 *   <li>失败(400 表示 least/most 暂未实现 / 无可用的临期包) → 把后端 message 写入 previewError,清空 previewAccount</li>
 *   <li>网络异常 → previewError 给"网络异常,请稍后重试"</li>
 * </ul>
 * 同一时刻只跑一个请求(in-flight 锁):mode 频繁切换时避免老请求覆盖新结果
 */
let previewInFlight = false
async function fetchPreviewAccount(mode: ConsumptionMode): Promise<void> {
  if (previewInFlight) return
  previewInFlight = true
  loadingPreview.value = true
  // 切 mode 立刻清空旧值,避免短暂显示上一个 mode 的结果
  previewAccount.value = null
  previewError.value = null
  try {
    const res = await fetch(`/api/settings/chat.consumption/preview?mode=${encodeURIComponent(mode)}`)
    if (res.status === 404) {
      previewError.value = '预览接口不存在,请检查后端版本'
      return
    }
    const body = (await res.json().catch(() => ({}))) as { data?: PreviewChatAccount; message?: string }
    if (res.ok && body.data) {
      previewAccount.value = body.data
    } else {
      previewError.value = body.message || `请求失败: ${res.status}`
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '网络异常'
    previewError.value = `网络异常: ${msg}`
  } finally {
    loadingPreview.value = false
    previewInFlight = false
  }
}

// ===== 持久化:从后端拉初始值 + Save 按钮 =====

/**
 * 后端返回的 chat.consumption 设置项的 value 结构
 */
interface ConsumptionSetting {
  mode: string
  accountId?: number | null
}

/**
 * "上次保存到后端的状态",用作 dirty 比较基准
 * <p>
 * null 表示后端从未保存过(首次进入页面),这种情况任何变更都视为 dirty
 */
const savedSnapshot = ref<ConsumptionSetting | null>(null)
const savingSettings = ref(false)
const settingsLoaded = ref(false)

/** 拉后端当前持久化的设置,作为 dirty 比较基准 */
async function loadSettings(): Promise<void> {
  try {
    const res = await fetch('/api/settings/chat.consumption')
    if (res.status === 404) {
      // 后端从未保存过,这是合法情况
      savedSnapshot.value = null
      return
    }
    if (!res.ok) throw new Error(`status=${res.status}`)
    const body = (await res.json()) as ConsumptionSetting
    savedSnapshot.value = body
    // 把后端的值同步到当前表单(让用户看到"当前生效的设置")
    if (body.mode && Object.values(MODE).includes(body.mode as ConsumptionMode)) {
      consumptionMode.value = body.mode as ConsumptionMode
    }
    // 无论 mode 是否 designated,都用后端当前值覆写本地 accountId(没有就清空)
    // 避免上次本地残留的 selectedAccountId 影响本次展示
    selectedAccountId.value = body.accountId ?? null
  } catch (e) {
    console.warn('[Settings] 拉取初始设置失败:', e)
    // 失败时按"未保存过"处理,让用户能正常操作
    savedSnapshot.value = null
  } finally {
    settingsLoaded.value = true
  }
}

/** 点 Save:把当前表单 PUT 到后端 */
async function saveSettings(): Promise<void> {
  if (savingSettings.value) return
  savingSettings.value = true
  try {
    const persisted: ConsumptionSetting = consumptionMode.value === MODE.DESIGNATED
      ? { mode: consumptionMode.value, accountId: selectedAccountId.value ?? null }
      : { mode: consumptionMode.value, accountId: null }

    const payload = {
      mode: consumptionMode.value,
      // 非指定模式时不带 accountId,后端会强制清空
      ...(consumptionMode.value === MODE.DESIGNATED && selectedAccountId.value != null
        ? { accountId: selectedAccountId.value }
        : {}),
    }
    const res = await fetch('/api/settings/chat.consumption', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `请求失败: ${res.status}`)
    }
    // 读掉响应体即可,UI 以用户当前刚选择的值为准,避免被后端回包 shape 变化/字段缺失打回默认值
    await res.json().catch(() => null)
    savedSnapshot.value = persisted
    // 非指定模式保存后,后端会清空 accountId,本地也立即对齐
    if (consumptionMode.value !== MODE.DESIGNATED) {
      selectedAccountId.value = null
    }
    message.success('已保存')
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`保存失败: ${msg}`)
  } finally {
    savingSettings.value = false
  }
}

/**
 * 防止后端清空 accountId 后,前端还残留着旧的 selectedAccountId,造成"看着选了但其实没生效"
 * <p>
 * 模式切到非指定时立即同步清空;模式切到指定时如果还没选过则不动
 * <p>
 * 同时在切到非指定模式时触发一次"指定账号预览"拉取,显示"当前 mode 后端会选哪个账号"
 */
watch(consumptionMode, (m) => {
  if (m === MODE.DESIGNATED) {
    // 切回指定模式:清掉预览状态(避免误显示);selectedAccountId 保留用户上次的编辑选择
    previewAccount.value = null
    previewError.value = null
  } else {
    // 切到非指定模式:清空编辑型 selectedAccountId,并触发预览
    selectedAccountId.value = null
    void fetchPreviewAccount(m)
  }
})

onMounted(async () => {
 //先拿账号列表,再拿后端配置并对齐显示。
 //这样 mode=designated 且带 accountId 时,下拉框选项已经准备好,不会出现
 // "先渲染默认值 → 再被后端值覆盖"的闪烁/错位。
 await loadAccounts()
 await loadSettings()
 // 如果后端持久化的是非指定模式,再补一次预览拉取(此时 consumptionMode 已经被 loadSettings 同步过)
 if (consumptionMode.value !== MODE.DESIGNATED) {
  await fetchPreviewAccount(consumptionMode.value)
 }
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

        <div v-else-if="showPreviewAccount" class="form-row">
          <label class="form-label" for="designated-account">指定账号</label>
          <div class="form-control">
            <n-spin :show="loadingPreview" size="small">
              <n-select
                id="designated-account"
                class="account-option-preview-host"
                :value="previewAccount?.accountId ?? null"
                :options="previewOptions"
                :render-label="renderPreviewLabel"
                placeholder="后端暂未选中任何账号"
                :disabled="true"
              />
            </n-spin>
            <p v-if="previewError" class="form-hint">
              <Icon name="settings" :size="12" />
              <span>{{ previewError }}</span>
            </p>
            <p v-else-if="!loadingPreview && !previewAccount" class="form-hint">
              切换到指定模式可手动选择账号。
            </p>
          </div>
        </div>
      </div>

      <div class="card-footer">
        <n-button
          type="primary"
          :disabled="!settingsLoaded || loadingAccounts"
          :loading="savingSettings"
          @click="saveSettings"
        >
          保存
        </n-button>
        <span v-if="settingsLoaded && savedSnapshot" class="footer-hint">
          已加载当前配置,点击“保存”将覆写后端设置
        </span>
        <span v-else-if="settingsLoaded" class="footer-hint">
          当前后端尚未保存过该设置
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
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

.card-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-top: 1px solid #ececf0;
  background: #fafbfc;
  border-bottom-left-radius: 8px;
  border-bottom-right-radius: 8px;
}

.footer-hint {
  font-size: 12px;
  color: #8c8c8c;
}

.footer-hint-dirty {
  color: #f0a020;
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

/* ========== 预览模式下的"指定账号"项:不可点击,文本置灰 ==========
   复用了 .form-hint / .footer-hint 的同款次要文本色 #8c8c8c,
   让用户在视觉上明确这条下拉是只读预览、不能编辑 */
:deep(.account-option-preview) {
  color: #8c8c8c;
}

/* 让 disabled 状态下 Naive UI 的 select 自身文字色也走同款灰,
   否则默认 disabled 是 var(--n-text-color-disabled)(#999 一类),与 label 不一致 */
:deep(.n-select.account-option-preview-host .n-base-selection-input),
:deep(.n-select.account-option-preview-host .n-base-selection-placeholder) {
  color: #8c8c8c;
}
</style>
