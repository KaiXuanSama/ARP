<script setup lang="ts">
/**
 * 下游账号管理(B 端) —— 管理 /v1/chat/completions 用的 API Key
 * <p>
 * 功能:
 * <ul>
 *   <li>分页列表(支持排序)</li>
 *   <li>新增:生成 32 位随机 key,弹窗显示完整明文一次</li>
 *   <li>编辑:修改 label / 消耗方式 / 指定账号 / 启用 / 积分上限 / 有效期</li>
 *   <li>删除</li>
 *   <li>复制 key 到剪贴板(列表中的遮蔽版用 "显示" 按钮可临时展开)</li>
 * </ul>
 * <p>
 * 消耗方式(consumption)值:
 * <ul>
 *   <li>designated —— 指定账号(下方 designatedAccountId 必须填)</li>
 *   <li>least / most / expiring —— 量少优先 / 量多优先 / 临期优先(本期 UI 仅显示,后端没接)</li>
 * </ul>
 */
import { computed, h, onMounted, ref, type VNode } from 'vue'
import {
  NButton,
  NDataTable,
  NInput,
  NInputNumber,
  NModal,
  NPagination,
  NSelect,
  NSwitch,
  NTag,
  useMessage,
  type DataTableColumns,
  type SelectGroupOption,
  type SelectOption,
} from 'naive-ui'
import Icon from './Icon.vue'
import { useAccountData } from '../composables/useAccountData'
import { useModels } from '../composables/useModels'
import type { CreditSnapshot } from '../utils/accountExtras'

const message = useMessage()
const accountStore = useAccountData()
const { models: allModels, ensureModelsLoaded } = useModels()

/**
 * 模型下拉选项(全模型清单,过滤空 id 防御)
 * <p>
 * 来源:useModels store,管理面板拉到的是后端维护的全集
 */
const modelOptions = computed<SelectOption[]>(() =>
  allModels.value
    .filter((m) => m.id && m.id.trim() !== '')
    .map((m) => ({
      value: m.id,
      label: m.family ? `${m.id} (${m.family})` : m.id,
    })),
)

/**
 * 表单"指定账号"下拉选项
 * <p>
 * 来源:useAccountData store 的 view(自动响应式,账号增删/积分刷新都会触发重算)
 * <p>
 * 渲染:label = 昵称,value = 账号 id,后端只需要整数 id
 */
interface AccountOption extends SelectOption {
  id: number
  uid: string
  nickname: string
  credit: CreditSnapshot | null
}

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
    }))
)

// ============== 类型 ==============

interface DownstreamKeyItem {
  id: number
  label: string
  apiKey: string
  apiKeyFull: boolean
  callCount: number
  usedCredits: number
  creditLimit: number | null
  expiresAt: number | null
  enabled: boolean
  consumption: string
  designatedAccountId: number | null
  /**
   * 支持的模型 id 列表
   * <ul>
   *   <li>{@code null} / {@code undefined} —— 未配置(回退全集)</li>
   *   <li>{@code []} —— 严格不放行</li>
   *   <li>{@code ["a","b"]} —— 白名单</li>
   * </ul>
   * 列表 API 总是返回数组(可能为空),空数组 ≠ 未配置
   */
  supportedModels: string[] | null
  createdAt: number
  updatedAt: number
}

interface PageResult<T> {
  total: number
  pages: number
  list: T[]
  pageNum: number
  pageSize: number
}

/**
 * 调用日志条目(对应后端 {@code CallLogItem})
 * <p>
 * content 是上游 chat 响应的"最终结算 chunk"原始 JSON 字符串,
 * CodeBuddy 字段随时变,前端按需 JSON.parse
 */
interface CallLogItem {
  id: number
  keyId: number | null
  accountId: number | null
  content: string
  createdAt: number
}

// ============== 列表状态 ==============

const list = ref<DownstreamKeyItem[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const orderBy = ref('created_at')
const asc = ref(false)
const loading = ref(false)
const loadError = ref<string | null>(null)

// ============== 模态框状态 ==============

const showFormModal = ref(false)
const editingId = ref<number | null>(null)
const formLabel = ref('')
const formConsumption = ref('designated')
const formDesignatedAccountId = ref<number | null>(null)
const formCreditLimit = ref<number | null>(null)
const formExpiresEnabled = ref(false)
const formExpiresAt = ref<number | null>(null)
/**
 * 表单中的"支持的模型"白名单
 * <p>
 * 三态:null(回退全集)/ [](严格不放行)/ ["a","b"](白名单)
 * <p>
 * <strong>开关 enableSupportedModels=false</strong> 表示"未配置"(回退全集),此时
 * formSupportedModels 即使有值也不发送到后端(后端收到 null = 沿用 existing)
 */
const enableSupportedModels = ref(false)
const formSupportedModels = ref<string[]>([])
const saving = ref(false)

/** 创建完成后,展示完整明文 key 的"一次性"模态框 */
const showNewKeyModal = ref(false)
const newlyCreatedKey = ref<DownstreamKeyItem | null>(null)
const copiedHint = ref(false)

/** 删除确认 */
const showDeleteModal = ref(false)
const deleteTarget = ref<DownstreamKeyItem | null>(null)
const deleting = ref(false)

/**
 * 调用日志模态框状态
 * <p>
 * 入口:点击表格"调用次数"列;模态框独立分页 + 排序,与主列表解耦
 */
const showCallLogModal = ref(false)
const callLogTarget = ref<DownstreamKeyItem | null>(null)
const callLogList = ref<CallLogItem[]>([])
const callLogTotal = ref(0)
const callLogPageNum = ref(1)
const callLogPageSize = ref(10)
const callLogOrderBy = ref('created_at')
const callLogAsc = ref(false)
const callLogLoading = ref(false)
const callLogError = ref<string | null>(null)
/** 展开查看 content 的行 id 集合 */
const expandedCallLogIds = ref<Set<number>>(new Set())

/**
 * 正在切换启用状态的 key id 集合
 * <p>
 * 用于 NSwitch 显示 loading + 禁用,避免用户在请求飞行中再次点击
 */
const togglingEnabledIds = ref<Set<number>>(new Set())

/**
 * 非 designated 模式命中的账号预览
 * <p>
 * key = mode(least / most / expiring),value = 命中的账号信息(uid + nickname)
 * <p>
 * 数据来源:列表加载后调一次 {@code POST /api/preview/chat-account-batch}
 * 把列表里出现的所有非 designated mode 一次性查一遍
 * <p>
 * 用 Map 而不是 Set 是因为前端要按 mode 查表,渲染"指定账号"列时直接 hit
 */
interface ModeHit {
  accountId: number
  uid: string
}

const previewByMode = ref<Map<string, ModeHit>>(new Map())

// ============== 消耗方式选项 ==============

const consumptionOptions: SelectOption[] = [
  { value: 'designated', label: '指定账号' },
  { value: 'least',      label: '量少优先' },
  { value: 'most',       label: '量多优先' },
  { value: 'expiring',   label: '临期优先' },
]

// 简单 4 位年份 + 月日时分 的 datetime-local 字符串转换
function toDateTimeLocal(ts: number | null): string {
  if (!ts) return ''
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

function fromDateTimeLocal(value: string): number | null {
  if (!value) return null
  const t = new Date(value).getTime()
  return Number.isFinite(t) ? t : null
}

const formExpiresLocal = computed({
  get: () => toDateTimeLocal(formExpiresAt.value),
  set: (v: string) => {
    formExpiresAt.value = fromDateTimeLocal(v)
  },
})

// ============== 列表加载 ==============

async function loadList(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    const res = await fetch('/api/downstream-keys/page', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        pageNum: pageNum.value,
        pageSize: pageSize.value,
        orderBy: orderBy.value,
        asc: asc.value,
      }),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const body = await res.json()
    const page: PageResult<DownstreamKeyItem> = body?.data
    if (!page) throw new Error('响应格式异常:缺少 data 字段')
    list.value = Array.isArray(page.list) ? page.list : []
    total.value = Number(page.total ?? 0)
    // 计算总页数(后端没回 pages 时用 total 算)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    loadError.value = `加载失败: ${msg}`
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
    // 列表加载成功后,触发一次"按方式预览"调用(只查列表中实际出现的非 designated mode)
    // 用 setTimeout 0 让 loading 状态先反映,避免接口耗时阻塞 UI
    void refreshPreviewByMode()
  }
}

/**
 * 收集当前列表中出现的所有非 designated 模式,一次性调批量预览接口
 * <p>
 * 调用次数 = 1(无论列表里有几个非 designated 行)
 * <p>
 * 每次分页/排序变化都会触发,但后端预览开销小(一次 SQL + 内存计算),可接受
 * <p>
 * 失败不影响列表显示 —— 指定账号列退化显示 '#{id}' 即可
 */
async function refreshPreviewByMode(): Promise<void> {
  // 1) 收集列表里出现的 mode(去重,过滤 designated)
  const NEED_PREVIEW_MODES = ['least', 'most', 'expiring']
  const modesInTable = new Set<string>()
  for (const row of list.value) {
    if (NEED_PREVIEW_MODES.includes(row.consumption)) {
      modesInTable.add(row.consumption)
    }
  }
  if (modesInTable.size === 0) {
    // 当前页没有非 designated 行 —— 清空预览缓存即可
    if (previewByMode.value.size > 0) previewByMode.value = new Map()
    return
  }
  try {
    const res = await fetch('/api/preview/chat-account-batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ modes: Array.from(modesInTable) }),
    })
    if (!res.ok) {
      console.warn(`[preview-batch] HTTP ${res.status}`)
      return
    }
    const body = await res.json()
    const data = body?.data || {}
    const next = new Map<string, ModeHit>()
    for (const [mode, preview] of Object.entries(data)) {
      if (preview && typeof preview === 'object') {
        const p = preview as { uid?: string; accountId?: number }
        if (p.uid && p.accountId != null) {
          next.set(mode, { accountId: p.accountId, uid: p.uid })
        }
      }
    }
    previewByMode.value = next
  } catch (e) {
    console.warn('[preview-batch] 调用失败', e)
  }
}

function onPageChange(p: number): void {
  pageNum.value = p
  void loadList()
}

function onPageSizeChange(s: number): void {
  pageSize.value = s
  pageNum.value = 1
  void loadList()
}

function onSortChange(value: { orderBy: string, asc: boolean }): void {
  orderBy.value = value.orderBy
  asc.value = value.asc
  pageNum.value = 1
  void loadList()
}

// ============== 表格列 ==============

const columns = computed<DataTableColumns<DownstreamKeyItem>>(() => [
  { title: '编号', key: 'id', width: 70 },
  {
    title: '标签',
    key: 'label',
    width: 100,
    ellipsis: { tooltip: true },
  },
  {
    title: '启用',
    key: 'enabled',
    width: 55,
    render: (row: DownstreamKeyItem): VNode => h(NSwitch, {
      value: row.enabled,
      size: 'small',
      loading: togglingEnabledIds.value.has(row.id),
      disabled: togglingEnabledIds.value.has(row.id),
      // 立即乐观更新 UI + 后台 PATCH,失败时回滚
      'onUpdate:value': (v: boolean) => toggleEnabled(row, v),
    }),
  },
  {
    title: 'API Key',
    key: 'apiKey',
    width: 150,
    render: (row: DownstreamKeyItem): VNode[] => [
      h('code', {
        style: {
          fontSize: '12px',
          fontFamily: "'SFMono-Regular', Consolas, monospace",
          color: '#666',
          wordBreak: 'break-all',
        },
      }, maskApiKey(row.apiKey)),
      h(NButton, {
        size: 'tiny', quaternary: true,
        title: '复制',
        onClick: () => copyToClipboard(row.apiKey),
      }, () => h(Icon, { name: 'copy', size: 14 })),
    ],
  },
  {
    title: '调用次数',
    key: 'callCount',
    width: 100,
    sorter: true,
    render: (row: DownstreamKeyItem): VNode => h(NButton, {
      size: 'tiny',
      quaternary: true,
      title: '点击查看调用日志',
      style: { padding: '0 4px' },
      onClick: () => openCallLogModal(row),
    }, () => row.callCount),
  },
  {
    title: '积分用量',
    key: 'creditUsage',
    width: 120,
    sorter: (a: DownstreamKeyItem, b: DownstreamKeyItem) =>
      (a.usedCredits ?? 0) - (b.usedCredits ?? 0),
    render: (row: DownstreamKeyItem): VNode => {
      const used = row.usedCredits == null ? '-' : row.usedCredits.toFixed(2)
      const limit = row.creditLimit == null ? '∞' : row.creditLimit.toFixed(1)
      // 已超限时把已用染红
      const usedStyle: Record<string, string> = {}
      if (row.usedCredits != null && row.creditLimit != null && row.usedCredits > row.creditLimit) {
        usedStyle.color = '#d03050'
        usedStyle.fontWeight = '500'
      }
      return h('span', {}, [
        h('span', { style: usedStyle }, used),
        ' / ',
        limit,
      ])
    },
  },
  {
    title: '消耗方式',
    key: 'consumption',
    width: 110,
    render: (row: DownstreamKeyItem): VNode => {
      const label = consumptionOptions.find((o) => o.value === row.consumption)?.label ?? row.consumption
      const type = row.consumption === 'designated' ? 'info'
        : row.consumption === 'expiring' ? 'warning'
        : 'default'
      return h(NTag, { size: 'small', type }, () => label)
    },
  },
  {
    title: '支持模型',
    key: 'supportedModels',
    width: 110,
    render: (row: DownstreamKeyItem): VNode => {
      // 三态渲染:
      //   - null/undefined → 全集("全部" 灰色)
      //   - []             → 严格不放行("不放行" 红色)
      //   - ["a","b"]      → 数字 + tooltip 列出 id
      if (row.supportedModels == null) {
        return h(NTag, { size: 'small', type: 'default' }, () => '全部')
      }
      if (row.supportedModels.length === 0) {
        return h(NTag, { size: 'small', type: 'error' }, () => '不放行')
      }
      const n = row.supportedModels.length
      const text = `${n} 个`
      return h('span', {
        title: row.supportedModels.join('\n'),
        style: { cursor: 'help', color: '#18a058' },
      }, text)
    },
  },
  {
    title: '指定账号',
    key: 'designatedAccountId',
    width: 130,
    render: (row: DownstreamKeyItem): VNode => {
      // 三种渲染路径:
      // 1) designated 模式 → 从 accountStore 查昵称(没有就回退 #id)
      // 2) least/most/expiring 模式 → 从 previewByMode 查预览结果(没拉到就回退 "-")
      // 3) 其他(designatedAccountId 也为 null) → "-"
      if (row.designatedAccountId != null) {
        const acct = accountStore.view.find((v) => v.id === row.designatedAccountId)
        const label = acct && acct.nickname && acct.nickname !== '未定义' ? acct.nickname : `#${row.designatedAccountId}`
        return h('span', { style: { fontSize: '12px' } }, label)
      }
      const hit = previewByMode.value.get(row.consumption)
      if (hit) {
        const acct = accountStore.view.find((v) => v.id === hit.accountId)
        const label = acct && acct.nickname && acct.nickname !== '未定义' ? acct.nickname : hit.uid
        return h('span', { style: { fontSize: '12px', color: '#18a058' } }, label)
      }
      return h('span', { style: { color: '#999', fontSize: '12px' } }, '-')
    },
  },
  {
    title: '有效期',
    key: 'expiresAt',
    width: 170,
    render: (row: DownstreamKeyItem): VNode => {
      if (row.expiresAt == null) return h('span', { style: { color: '#999' } }, '永久')
      return h('span', {}, new Date(row.expiresAt).toLocaleString('zh-CN'))
    },
  },
  { title: '创建时间', key: 'createdAt', width: 170, sorter: 'default', render: (r) => formatTime(r.createdAt) },
  {
    title: '操作',
    key: 'actions',
    width: 140,
    fixed: 'right',
    render: (row: DownstreamKeyItem): VNode[] => [
      h(NButton, {
        size: 'tiny', quaternary: true, onClick: () => openEditModal(row),
      }, () => [h(Icon, { name: 'edit', size: 14 }), ' 编辑']),
      h(NButton, {
        size: 'tiny', quaternary: true, type: 'error',
        onClick: () => openDeleteModal(row),
      }, () => [h(Icon, { name: 'delete', size: 14 }), ' 删除']),
    ],
  },
])

// ============== 调用日志表格列 ==============

/**
 * 调用日志表格列定义
 * <p>
 * 4 列:编号 / 时间 / keyId / content(可点击展开)
 * <p>
 * content 是上游 chunk 原始 JSON 字符串,默认折叠显示截断版,点击行展开查看完整内容
 */
const callLogColumns = computed<DataTableColumns<CallLogItem>>(() => [
  { title: '编号', key: 'id', width: 70 },
  {
    title: '时间',
    key: 'createdAt',
    width: 150,
    sorter: 'default',
    render: (row: CallLogItem): VNode => h('span', { style: { fontSize: '12px' } }, formatTime(row.createdAt)),
  },
  {
    title: '账号',
    key: 'accountId',
    width: 130,
    render: (row: CallLogItem): VNode => {
      // null = 老数据(无法追溯当时命中的账号)或账号已删
      if (row.accountId == null) {
        return h('span', { style: { color: '#999' } }, '-')
      }
      // 从 accountStore.view 查昵称(同主列表"指定账号"列的解析方式)
      // 没有就回退 #id,既保证可读也保留可点击追溯
      const acct = accountStore.view.find((v) => v.id === row.accountId)
      const label = acct && acct.nickname && acct.nickname !== '未定义'
        ? acct.nickname
        : `#${row.accountId}`
      return h('span', { style: { fontSize: '12px' } }, label)
    },
  },
  {
    title: 'chunk 内容(点击行展开)',
    key: 'content',
    render: (row: CallLogItem): VNode[] => {
      const expanded = expandedCallLogIds.value.has(row.id)
      const preview = row.content.length > 80
        ? row.content.substring(0, 80) + '…'
        : row.content
      return [
        h('code', {
          style: {
            fontSize: '12px',
            fontFamily: "'SFMono-Regular', Consolas, monospace",
            color: expanded ? '#333' : '#666',
            whiteSpace: expanded ? 'pre-wrap' : 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            display: 'block',
            maxWidth: '100%',
            cursor: 'pointer',
          },
          onClick: () => toggleCallLogExpand(row.id),
        }, expanded ? row.content : preview),
      ]
    },
  },
])

// ============== 工具函数 ==============

/**
 * 前端脱敏:把 32+ 位 key 显示为前 4 + 中间 * + 后 4
 * <p>
 * 注意:
 * <ul>
 *   <li>后端始终返回明文(分页 / 详情 都给全量),方便复制按钮直接用</li>
 *   <li>前端只"显示"时脱敏,内存里 row.apiKey 仍是完整 key</li>
 *   <li>复制按钮永远复制 row.apiKey (明文),不会复制脱敏版</li>
 * </ul>
 */
/**
 * 把 CreditSnapshot 折叠成"已用/总量"两个数
 * <p>
 * 算法与 AccountManagement 表格"积分剩余"列完全一致(Σ packages.cycleCapacityRemain / Size)
 * <p>
 * 注:这里叫"已用/总量"是为了和用户措辞对齐;本类 col 名是"积分用量"
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
 * "指定账号"下拉项的 label 渲染: 昵称(余量/总量)
 * <p>
 * 例:
 * <ul>
 *   <li>"凯旋Sama（3067.6 / 3100）"</li>
 *   <li>"64980534（-）" — 无 credit 快照时退化为 "-"</li>
 * </ul>
 * <p>
 * 用 NSelect 的 :render-label 函数 prop 实现,
 * 避免再写一个 .account-option CSS 类(原 Settings.vue 那种)。
 */
function renderAccountLabel(option: SelectOption | SelectGroupOption): VNode {
  const opt = option as AccountOption
  const { remain, size } = summarizeCredit(opt.credit)
  const creditText = opt.credit
    ? `${remain.toFixed(1)} / ${size.toFixed(0)}`
    : '-'
  const text = `${opt.nickname || opt.uid} (${creditText})`
  return h('div', { style: 'line-height: 1.4;' }, text)
}

function maskApiKey(key: string): string {
  if (!key || key.length <= 12) return key
  return key.substring(0, 4) + '********' + key.substring(key.length - 4)
}

function formatTime(ts: number | null | undefined): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

async function copyToClipboard(text: string): Promise<void> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      if (!ok) throw new Error('execCommand copy failed')
    }
    message.success('已复制到剪贴板')
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`复制失败: ${msg},请手动选中复制`)
  }
}

// ============== 新增 / 编辑 ==============

function resetForm(): void {
  editingId.value = null
  formLabel.value = ''
  formConsumption.value = 'designated'
  formDesignatedAccountId.value = null
  formCreditLimit.value = null
  formExpiresEnabled.value = false
  formExpiresAt.value = null
  enableSupportedModels.value = false
  formSupportedModels.value = []
}

function openCreateModal(): void {
  resetForm()
  showFormModal.value = true
}

function openEditModal(row: DownstreamKeyItem): void {
  resetForm()
  editingId.value = row.id
  formLabel.value = row.label
  formConsumption.value = row.consumption
  formDesignatedAccountId.value = row.designatedAccountId
  formCreditLimit.value = row.creditLimit
  formExpiresAt.value = row.expiresAt
  formExpiresEnabled.value = row.expiresAt != null
  // 白名单三态映射:
  //   - null/undefined → 未配置(开关关,空数组)
  //   - []  → 严格不放行(开关开,空数组)
  //   - ["a","b"] → 开关开,白名单
  if (Array.isArray(row.supportedModels)) {
    enableSupportedModels.value = true
    formSupportedModels.value = [...row.supportedModels]
  } else {
    enableSupportedModels.value = false
    formSupportedModels.value = []
  }
  showFormModal.value = true
}

async function saveForm(): Promise<void> {
  if (!formLabel.value.trim()) {
    message.warning('标签不能为空')
    return
  }
  if (formConsumption.value === 'designated' && formDesignatedAccountId.value == null) {
    message.warning('消耗方式为"指定账号"时,必须填写指定账号 id')
    return
  }
  saving.value = true
  try {
    const body: Record<string, unknown> = {
      label: formLabel.value.trim(),
      consumption: formConsumption.value,
      designatedAccountId: formConsumption.value === 'designated' ? formDesignatedAccountId.value : null,
      creditLimit: formCreditLimit.value,
      expiresAt: formExpiresEnabled.value ? formExpiresAt.value : null,
      // 白名单三态映射(对齐后端 creditLimit/expiresAt 的 null=清空契约):
      //   - enableSupportedModels=false → 显式传 null
      //     后端:写 SQL NULL,/v1/models 回退全集(回退默认行为)
      //   - enableSupportedModels=true + []      → 严格不放行(后端会写 "[]")
      //   - enableSupportedModels=true + ["a",…] → 白名单覆盖
      // 关键:必须显式传 null,不能 omit —— 后端约定 null = 清空,字段缺失也会
      // 序列化成 null(JSON.stringify 不带字段 = 没有该 key),但显式 null 更稳妥,
      // 也让"已关闭限制"这个意图在 wire 上可见
      supportedModels: enableSupportedModels.value ? [...formSupportedModels.value] : null,
    }
    // 删除 undefined 字段(避免 "undefined" 字符串),null 保留 —— null 是合法
    // "清空"信号,不是缺失
    for (const k of Object.keys(body)) {
      if (body[k] === undefined) delete body[k]
    }
    const url = editingId.value == null
      ? '/api/downstream-keys'
      : `/api/downstream-keys/${editingId.value}`
    const res = await fetch(url, {
      method: editingId.value == null ? 'POST' : 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `HTTP ${res.status}`)
    }
    const respData = await res.json()
    const saved: DownstreamKeyItem = respData?.data
    if (!saved) throw new Error('响应缺少 data 字段')
    showFormModal.value = false
    if (editingId.value == null) {
      // 创建场景:弹出"完整 key"模态框(只此一次显示明文)
      newlyCreatedKey.value = saved
      copiedHint.value = false
      showNewKeyModal.value = true
    } else {
      message.success('更新成功')
    }
    await loadList()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`保存失败: ${msg}`)
  } finally {
    saving.value = false
  }
}

function closeNewKeyModal(): void {
  showNewKeyModal.value = false
  newlyCreatedKey.value = null
}

// ============== 调用日志 ==============

/**
 * 打开调用日志模态框
 * <p>
 * 入口:点击"调用次数"列的按钮;模态框内部独立分页
 */
function openCallLogModal(row: DownstreamKeyItem): void {
  callLogTarget.value = row
  callLogPageNum.value = 1
  expandedCallLogIds.value = new Set()
  showCallLogModal.value = true
  void loadCallLogs()
}

/**
 * 关闭调用日志模态框 —— 清空状态,避免下次打开时残留旧数据
 */
function closeCallLogModal(): void {
  showCallLogModal.value = false
  callLogList.value = []
  callLogError.value = null
  expandedCallLogIds.value = new Set()
}

/**
 * 拉取一页调用日志
 */
async function loadCallLogs(): Promise<void> {
  if (!callLogTarget.value) return
  callLogLoading.value = true
  callLogError.value = null
  try {
    const res = await fetch(
      `/api/downstream-keys/${callLogTarget.value.id}/call-logs/page`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          pageNum: callLogPageNum.value,
          pageSize: callLogPageSize.value,
          orderBy: callLogOrderBy.value,
          asc: callLogAsc.value,
        }),
      },
    )
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const body = await res.json()
    const page: PageResult<CallLogItem> = body?.data
    if (!page) throw new Error('响应格式异常:缺少 data 字段')
    callLogList.value = Array.isArray(page.list) ? page.list : []
    callLogTotal.value = Number(page.total ?? 0)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    callLogError.value = `加载失败: ${msg}`
    callLogList.value = []
    callLogTotal.value = 0
  } finally {
    callLogLoading.value = false
  }
}

function onCallLogPageChange(p: number): void {
  callLogPageNum.value = p
  void loadCallLogs()
}

function onCallLogPageSizeChange(s: number): void {
  callLogPageSize.value = s
  callLogPageNum.value = 1
  void loadCallLogs()
}

function onCallLogSortChange(value: { orderBy: string, asc: boolean }): void {
  callLogOrderBy.value = value.orderBy
  callLogAsc.value = value.asc
  callLogPageNum.value = 1
  void loadCallLogs()
}

function toggleCallLogExpand(id: number): void {
  const next = new Set(expandedCallLogIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expandedCallLogIds.value = next
}

// ============== 删除 ==============

function openDeleteModal(row: DownstreamKeyItem): void {
  deleteTarget.value = row
  showDeleteModal.value = true
}

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    const res = await fetch(`/api/downstream-keys/${deleteTarget.value.id}`, { method: 'DELETE' })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `HTTP ${res.status}`)
    }
    message.success(`已删除 #${deleteTarget.value.id}`)
    showDeleteModal.value = false
    deleteTarget.value = null
    await loadList()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`删除失败: ${msg}`)
  } finally {
    deleting.value = false
  }
}

/**
 * 切换单个 key 的启用状态
 * <p>
 * 行为:
 * <ul>
 *   <li>乐观更新 UI(立刻把开关切到目标状态),失败时回滚</li>
 *   <li>请求飞行中把 id 加进 togglingEnabledIds,让 NSwitch 显示 loading + 禁用,防止连点</li>
 *   <li>不重载整个列表 —— 只更新这一行,响应更迅速</li>
 * </ul>
 */
async function toggleEnabled(row: DownstreamKeyItem, newEnabled: boolean): Promise<void> {
  const id = row.id
  const oldEnabled = row.enabled
  // 乐观更新
  row.enabled = newEnabled
  // 进入"正在切换"集合
  togglingEnabledIds.value.add(id)
  togglingEnabledIds.value = new Set(togglingEnabledIds.value)
  try {
    const res = await fetch(`/api/downstream-keys/${id}/enabled`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: newEnabled }),
    })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `HTTP ${res.status}`)
    }
    // 用服务端返回的精确值回填(防御性)
    const body = await res.json()
    const saved = body?.data
    if (saved && typeof saved.enabled === 'boolean') {
      row.enabled = saved.enabled
    }
    message.success(`Key #${id} 已${newEnabled ? '启用' : '停用'}`)
  } catch (e: unknown) {
    // 回滚
    row.enabled = oldEnabled
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`切换启用状态失败: ${msg}`)
  } finally {
    togglingEnabledIds.value.delete(id)
    togglingEnabledIds.value = new Set(togglingEnabledIds.value)
  }
}

onMounted(async () => {
  // 列表数据 + 账号下拉选项 + 模型清单(后端全集,给"支持的模型"多选用)+ 账号积分快照
  await Promise.allSettled([
    loadList(),
    accountStore.ensureAccountsLoaded(),
    ensureModelsLoaded(),
  ])
  // 积分快照拉取是后台异步,失败也不阻塞页面
  void accountStore.ensureExtrasLoaded()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="toolbar-left">
        <n-input
          placeholder="搜索标签(暂未启用,后续扩展)"
          size="small"
          style="width:240px"
          disabled
        >
          <template #prefix>
            <Icon name="search" :size="14" />
          </template>
        </n-input>
      </div>
      <div class="toolbar-right">
        <n-button quaternary @click="loadList" :loading="loading">
          <template #icon>
            <Icon name="refresh" :size="14" />
          </template>
          刷新
        </n-button>
        <n-button type="primary" @click="openCreateModal">
          <template #icon>
            <Icon name="plus" :size="14" />
          </template>
          新增下游 Key
        </n-button>
      </div>
    </div>

    <n-card :bordered="false" class="table-card">
      <!-- 排序工具栏:表格上方靠左(参考签到历史模态框的同样设计) -->
      <div class="sort-bar">
        <span class="sort-label">排序：</span>
        <n-button
          size="tiny"
          :type="orderBy === 'created_at' && !asc ? 'primary' : 'default'"
          @click="onSortChange({ orderBy: 'created_at', asc: false })"
        >创建时间倒序</n-button>
        <n-button
          size="tiny"
          :type="orderBy === 'created_at' && asc ? 'primary' : 'default'"
          @click="onSortChange({ orderBy: 'created_at', asc: true })"
        >创建时间正序</n-button>
        <n-button
          size="tiny"
          :type="orderBy === 'id' ? 'primary' : 'default'"
          @click="onSortChange({ orderBy: 'id', asc: false })"
        >按 ID</n-button>
        <n-button
          size="tiny"
          :type="orderBy === 'call_count' ? 'primary' : 'default'"
          @click="onSortChange({ orderBy: 'call_count', asc: false })"
        >调用次数</n-button>
        <n-button
          size="tiny"
          :type="orderBy === 'used_credits' ? 'primary' : 'default'"
          @click="onSortChange({ orderBy: 'used_credits', asc: false })"
        >已用积分</n-button>
      </div>

      <div v-if="loadError" class="error-banner">{{ loadError }}</div>

      <n-data-table
        :columns="columns"
        :data="list"
        :loading="loading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: DownstreamKeyItem) => row.id"
        :pagination="false"
        :scroll-x="1540"
      />
      <div v-if="!loading && list.length === 0 && !loadError" class="empty-hint">
        暂无下游 API Key,点击右上角"新增下游 Key"开始
      </div>

      <!-- 分页(放在 n-card 内,贴近表格底部) -->
      <div class="pagination-row">
        <span class="summary">共 {{ total }} 条 · 第 {{ pageNum }} 页</span>
        <n-pagination
          :page="pageNum"
          :page-size="pageSize"
          :item-count="total"
          :page-sizes="[10, 20, 50, 100]"
          show-size-picker
          @update:page="onPageChange"
          @update:page-size="onPageSizeChange"
        />
      </div>
    </n-card>

    <!-- 新增 / 编辑模态框 -->
    <n-modal
      v-model:show="showFormModal"
      preset="card"
      :title="editingId == null ? '新增下游 Key' : `编辑 Key #${editingId}`"
      style="width: 560px;"
    >
      <n-space vertical size="medium">
        <div>
          <div class="form-label">标签 *</div>
          <n-input v-model:value="formLabel" placeholder="如:Alice 的 iPad" maxlength="64" />
        </div>

        <div>
          <div class="form-label">消耗方式 *</div>
          <n-select v-model:value="formConsumption" :options="consumptionOptions" />
        </div>

        <div v-if="formConsumption === 'designated'">
          <div class="form-label">指定账号 *</div>
          <n-select
            v-model:value="formDesignatedAccountId"
            :options="accountOptions"
            placeholder="请选择账号(对应 workbuddy_account.id)"
            :render-label="renderAccountLabel"
            filterable
            clearable
            style="width: 100%;"
          />
        </div>

        <div>
          <div class="form-label">积分上限(留空 = 不限)</div>
          <n-input-number v-model:value="formCreditLimit" :min="0" :precision="1" placeholder="例如 100" style="width: 100%;" />
        </div>

        <div>
          <div class="form-label">支持的模型</div>
          <n-space align="center" :wrap="false">
            <n-switch v-model:value="enableSupportedModels" />
            <span style="font-size: 12px; color: #666;">
              {{ enableSupportedModels ? '白名单模式(下方选中的模型才会在 /v1/models 中出现)' : '未配置(/v1/models 走全集)' }}
            </span>
          </n-space>
          <n-select
            v-if="enableSupportedModels"
            v-model:value="formSupportedModels"
            :options="modelOptions"
            multiple
            filterable
            clearable
            placeholder="不选 = 严格不放行(返回空模型列表)"
            style="width: 100%; margin-top: 8px;"
          />
        </div>

        <div>
          <div class="form-label">启用有效期</div>
          <n-switch v-model:value="formExpiresEnabled" />
          <input
            v-if="formExpiresEnabled"
            v-model="formExpiresLocal"
            type="datetime-local"
            class="dt-input"
          />
        </div>
      </n-space>

      <template #footer>
        <n-space justify="end">
          <n-button @click="showFormModal = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="saveForm">
            {{ editingId == null ? '创建' : '保存' }}
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 创建后展示完整 key 的一次性模态框 -->
    <n-modal
      v-model:show="showNewKeyModal"
      preset="card"
      title="✓ Key 创建成功"
      style="width: 580px;"
      :mask-closable="false"
      :closable="false"
    >
      <n-space vertical size="medium">
        <n-alert type="warning" :show-icon="true">
          <strong>请立即保存此 Key</strong> —— 此完整 key 仅显示这一次,关闭后将无法再次查看完整值。
        </n-alert>

        <div v-if="newlyCreatedKey">
          <div class="form-label">标签</div>
          <div style="margin-bottom: 12px;">{{ newlyCreatedKey.label }}</div>

          <div class="form-label">API Key(完整明文)</div>
          <div class="key-display">
            <code>{{ newlyCreatedKey.apiKey }}</code>
            <n-button size="tiny" quaternary @click="copyToClipboard(newlyCreatedKey!.apiKey)">
              <template #icon><Icon name="copy" :size="14" /></template>
              复制
            </n-button>
          </div>
        </div>
      </n-space>

      <template #footer>
        <n-space justify="space-between" align="center" style="width: 100%;">
          <span v-if="copiedHint" style="color: #18a058; font-size: 12px;">
            <Icon name="check-circle" :size="14" /> 已复制到剪贴板
          </span>
          <span v-else></span>
          <n-button type="primary" @click="closeNewKeyModal">我已保存,关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 删除确认 -->
    <n-modal
      v-model:show="showDeleteModal"
      preset="card"
      title="确认删除"
      style="width: 440px;"
    >
      <p>确定删除 Key <strong>#{{ deleteTarget?.id }}</strong>({{ deleteTarget?.label }})吗?</p>
      <p style="color: #d03050; font-size: 12px;">此操作不可恢复,使用此 Key 的下游将立即无法调用 chat。</p>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showDeleteModal = false">取消</n-button>
          <n-button type="error" :loading="deleting" @click="confirmDelete">确认删除</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 调用日志模态框(点击"调用次数"列触发) -->
    <n-modal
      v-model:show="showCallLogModal"
      preset="card"
      :title="`调用日志 · Key #${callLogTarget?.id ?? ''} (${callLogTarget?.label ?? ''})`"
      style="width: 980px;"
      :on-after-leave="closeCallLogModal"
    >
      <!-- 排序工具栏 -->
      <n-space align="center" size="small" style="margin-bottom: 8px;">
        <span class="history-summary">排序：</span>
        <n-button
          size="tiny"
          :type="callLogOrderBy === 'created_at' && !callLogAsc ? 'primary' : 'default'"
          @click="onCallLogSortChange({ orderBy: 'created_at', asc: false })"
        >时间倒序</n-button>
        <n-button
          size="tiny"
          :type="callLogOrderBy === 'created_at' && callLogAsc ? 'primary' : 'default'"
          @click="onCallLogSortChange({ orderBy: 'created_at', asc: true })"
        >时间正序</n-button>
        <n-button
          size="tiny"
          :type="callLogOrderBy === 'id' ? 'primary' : 'default'"
          @click="onCallLogSortChange({ orderBy: 'id', asc: false })"
        >按 ID</n-button>
      </n-space>
      <div v-if="callLogError" class="error-banner">{{ callLogError }}</div>
      <n-data-table
        :columns="callLogColumns"
        :data="callLogList"
        :loading="callLogLoading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: CallLogItem) => row.id"
        :pagination="false"
        :max-height="420"
        :scroll-x="970"
      />
      <div v-if="!callLogLoading && callLogList.length === 0 && !callLogError" class="empty-hint">
        该 Key 暂无调用日志
      </div>
      <!-- 分页 -->
      <div class="pagination-row" style="margin-top: 12px;">
        <span class="summary">共 {{ callLogTotal }} 条 · 第 {{ callLogPageNum }} 页</span>
        <n-pagination
          :page="callLogPageNum"
          :page-size="callLogPageSize"
          :item-count="callLogTotal"
          :page-sizes="[10, 20, 50, 100]"
          show-size-picker
          @update:page="onCallLogPageChange"
          @update:page-size="onCallLogPageSizeChange"
        />
      </div>
      <template #footer>
        <n-space justify="end">
          <n-button @click="closeCallLogModal">关闭</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
/* 与 AccountManagement 风格一致 */
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  /* 关键:防止 flex 子项溢出导致外层 .content 横向滚动 */
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-right {
  display: flex;
  gap: 8px;
}

.table-card {
  background: #fff;
  border-radius: 8px;
  /* 关键:让 n-card 自己处理溢出,n-data-table 内的 scroll-x 在这里产生横向滚动 */
  overflow: hidden;
}

/* 排序工具栏(表格上方靠左) */
.sort-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.sort-label {
  color: #666;
  font-size: 12px;
}

/* 分页行(表格下方) */
.pagination-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.summary {
  color: #666;
  font-size: 12px;
}

/* 错误横幅 */
.error-banner {
  color: #d03050;
  background: #fff2f0;
  border: 1px solid #ffccc7;
  padding: 8px 12px;
  border-radius: 4px;
  margin-bottom: 12px;
}

/* 空状态(对齐 AccountManagement 的 .empty-hint 风格) */
.empty-hint {
  padding: 24px 12px;
  text-align: center;
  color: #999;
}

/* 表单标签 */
.form-label {
  font-size: 12px;
  color: #555;
  margin-bottom: 6px;
}

/* 创建后展示完整 key 的高亮框 */
.key-display {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: #f5f5f5;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
}
.key-display code {
  flex: 1;
  font-size: 13px;
  word-break: break-all;
  color: #18a058;
  user-select: all;
}

/* datetime-local 原生 input 样式(Naive UI 没有该类型) */
.dt-input {
  width: 100%;
  margin-top: 6px;
  padding: 4px 8px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 13px;
  background: #fff;
  box-sizing: border-box;
}
.dt-input:focus {
  outline: none;
  border-color: #18a058;
}
</style>
