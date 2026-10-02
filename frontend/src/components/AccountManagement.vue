<script setup lang="ts">
import { computed, h, nextTick, onMounted, onUnmounted, ref, type VNode } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NDropdown,
  NInput,
  NModal,
  NPagination,
  NSpin,
  NSpace,
   NSwitch,
  NUpload,
  useMessage,
  type DataTableColumns,
  type UploadFileInfo,
} from 'naive-ui'
import Icon from './Icon.vue'
import type {
  CheckinSnapshot,
  CreditPackage,
  CreditSnapshot,
  UsageSnapshot,
} from '../utils/accountExtras'
import { useAccountData, type AccountDataView } from '../composables/useAccountData'
import { authFetch } from '../utils/auth'

interface WorkbuddyInfo {
  account?: {
    uid?: string
    nickname?: string
    uin?: string
    type?: string
    pluginEnabled?: boolean
    phoneNumber?: string
  }
  auth?: {
    accessToken?: string
    tokenType?: string
    scope?: string
    domain?: string
    expiresAt?: number
    refreshExpiresAt?: number
  }
}

interface ApiKeyOnlyPayload {
  nickname: string
  apiKey: string
}

interface AccountRow {
  id: number
  uid: string
  nickname: string
  primaryCredential: string
  apiKey: string | null
  accessToken: string | null
   enabled: boolean
  updatedAt: number
  /** 凭证过期时间(毫秒时间戳);undefined/null = 无过期信息(API Key 账号) */
  credentialExpiresAt?: number | null
  /** refreshToken 过期时间(毫秒时间戳);用于提示"续期窗口还剩多久" */
  refreshExpiresAt?: number | null
  credit?: CreditSnapshot
  usage?: UsageSnapshot
  checkin?: CheckinSnapshot
  checkinProgress?: 'pending' | 'running' | 'done' | 'error'
}

/**
 * 流量包视图的一行(扁平化:一个账号多个流量包 → 多个 PackageRow)
 * <p>
 * 携带原 AccountRow 的 id/nickname/uid 用于"编号"与"昵称"列展示,
 * 以及单个 CreditPackage 用于"流量包名称/余量/过期时间"三列
 * <p>
 * rowKey 用 accountId + packageCode 复合,避免同账号多流量包出现重复 key
 */
interface PackageRow {
  accountId: number
  uid: string
  nickname: string
  package: CreditPackage
}

interface CheckinStreamEvent {
  type: 'started' | 'result' | 'summary'
  accountId: number | null
  uid: string | null
  nickname: string | null
  position: number | null
  total: number | null
  status: string | null
  message: string | null
  checkinTime: number | null
  okCount: number | null
  alreadyCount: number | null
  errCount: number | null
  durationMs: number | null
}

const message = useMessage()

const showChooser = ref(false)

const showDetail = ref(false)
const loadingDetail = ref(false)
const savingDetail = ref(false)
const detailInfo = ref<WorkbuddyInfo | null>(null)
const showAccessToken = ref(false)

const showApiKeyForm = ref(false)
const apiKeyForm = ref<ApiKeyOnlyPayload>({ nickname: '', apiKey: '' })
const savingApiKey = ref(false)

// ===== 扫码登录（2026-10 插件授权新链路） =====
//
// 旧书签回传方案已随官方封堵 /console/login/enterprise 而失效，改为服务端插件授权流：
//   ① 前端调 POST /api/accounts/plugin-login/session → 后端伪装官方 CLI 调上游
//      /v2/plugin/auth/state 拿官方登录页链接
//   ② 用户浏览器打开链接扫码登录（无需书签，凭证不经浏览器）
//   ③ 前端每 3 秒调 POST /api/accounts/plugin-login/{id}/poll → 后端轮询上游
//      领取凭证并落库，按 status 返回 pending / success / expired / error
//   ④ success 后刷新账号列表与附加信息

/** 扫码登录弹窗 */
const showScanLogin = ref(false)
/** 创建会话中 */
const creatingSession = ref(false)
/** 当前登录会话 */
const scanSession = ref<ScanLoginSession | null>(null)
/** 剩余秒数（倒计时） */
const scanRemaining = ref(0)
/** 前端轮询授权状态的定时器 */
let scanPollTimer: number | null = null
/** 倒计时定时器 */
let scanCountdownTimer: number | null = null

/** 后端 /api/accounts/plugin-login/session 的响应 */
interface ScanLoginSession {
  /** 授权会话 id（后端随机生成，轮询/取消时使用） */
  id: string
  /** 官方登录页链接（后端已做防钓鱼校验） */
  loginUrl: string
  /** 会话过期时间（毫秒时间戳） */
  expiresAt: number
  /** 建议的轮询间隔（秒） */
  interval: number
}

/** 后端 /api/accounts/plugin-login/{id}/poll 的响应 */
interface ScanLoginPoll {
  /** pending | success | expired | error */
  status: 'pending' | 'success' | 'expired' | 'error'
  uid?: string | null
  nickname?: string | null
  expiresAt?: number | null
  message?: string | null
}

/**
 * 「扫码登录」入口 —— 创建授权会话并打开扫码弹窗
 * <p>
 * 新链路无需风险告知弹窗：登录链接是官方地址（后端已校验 host 为
 * copilot.tencent.com），凭证由服务端领取、永不出服务端，也没有书签脚本。
 */
async function openScanLogin(): Promise<void> {
  showChooser.value = false
  showScanLogin.value = true
  creatingSession.value = true
  scanSession.value = null
  scanRemaining.value = 0

  try {
    const res = await authFetch('/api/accounts/plugin-login/session', { method: 'POST' })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) {
      throw new Error(body?.message || `请求失败: ${res.status}`)
    }
    scanSession.value = body as ScanLoginSession
    startScanCountdown()
    startScanPolling()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`创建登录会话失败: ${msg}`)
    showScanLogin.value = false
  } finally {
    creatingSession.value = false
  }
}

/** 倒计时（显示会话剩余有效时间） */
function startScanCountdown(): void {
  stopScanCountdown()
  const tick = () => {
    if (!scanSession.value) return
    const left = Math.max(0, Math.floor((scanSession.value.expiresAt - Date.now()) / 1000))
    scanRemaining.value = left
    if (left <= 0) {
      stopScanCountdown()
    }
  }
  tick()
  scanCountdownTimer = window.setInterval(tick, 1000)
}

function stopScanCountdown(): void {
  if (scanCountdownTimer !== null) {
    window.clearInterval(scanCountdownTimer)
    scanCountdownTimer = null
  }
}

/**
 * 轮询后端授权状态（后端自带对上游的 3 秒节流，前端同频轮询即可）
 * <p>
 * status 分支：
 * - pending：继续等下一轮
 * - success：提示账号信息，关弹窗，刷新列表与附加信息
 * - expired / error：停止轮询并提示
 */
function startScanPolling(): void {
  stopScanPolling()
  scanPollTimer = window.setInterval(async () => {
    if (!scanSession.value) {
      stopScanPolling()
      return
    }
    if (scanRemaining.value <= 0) {
      stopScanPolling()
      return
    }
    try {
      const res = await authFetch(`/api/accounts/plugin-login/${scanSession.value.id}/poll`, {
        method: 'POST',
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) {
        throw new Error(body?.message || `请求失败: ${res.status}`)
      }
      const poll = body as ScanLoginPoll
      if (poll.status === 'pending') {
        return
      }
      if (poll.status === 'success') {
        const name = poll.nickname || poll.uid || '未知账号'
        message.success(`已导入账号：${name}`)
        closeScanLogin()
        await loadAccounts()
        await refreshExtras()
        return
      }
      if (poll.status === 'expired') {
        stopScanPolling()
        message.warning('登录会话已过期，请关闭后重新发起扫码登录')
        return
      }
      // error：展示后端 message，停止轮询（多为上游异常，重试需重新生成链接）
      stopScanPolling()
      message.error(poll.message || '授权状态获取失败，请重新发起')
    } catch (e) {
      // 单次轮询失败静默忽略，下个周期重试
    }
  }, 3000)
}

function stopScanPolling(): void {
  if (scanPollTimer !== null) {
    window.clearInterval(scanPollTimer)
    scanPollTimer = null
  }
}

/** 关闭弹窗：停止定时器并通知后端取消会话（失败静默——后端会话 5 分钟自动过期） */
function closeScanLogin(): void {
  const session = scanSession.value
  if (session && scanRemaining.value > 0) {
    authFetch(`/api/accounts/plugin-login/${session.id}`, { method: 'DELETE' }).catch(() => {})
  }
  showScanLogin.value = false
  scanSession.value = null
  scanRemaining.value = 0
  stopScanCountdown()
  stopScanPolling()
}

/** 复制登录链接到剪贴板（用于在无二维码的情况下手动打开） */
async function copyLoginUrl(): Promise<void> {
  const url = scanSession.value?.loginUrl
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    message.success('登录链接已复制')
  } catch (e) {
    message.error('复制失败，请手动选中链接复制')
  }
}

/** 在新标签页打开登录链接 */
function openLoginUrl(): void {
  const url = scanSession.value?.loginUrl
  if (url) {
    window.open(url, '_blank', 'noopener,noreferrer')
  }
}

/** 显示剩余时间（mm:ss） */
const scanRemainingText = computed(() => {
  const s = scanRemaining.value
  const m = Math.floor(s / 60)
  const sec = s % 60
  return `${m}:${String(sec).padStart(2, '0')}`
})

/** 流量包明细弹窗 */
const showPackages = ref(false)
const packagesRow = ref<AccountRow | null>(null)

function openPackagesModal(row: AccountRow): void {
  packagesRow.value = row
  showPackages.value = true
}

const tableData = ref<AccountRow[]>([])
const checkingIn = ref(false)
const togglingEnabledIds = ref<Set<number>>(new Set())

/**
 * 表格视图模式
 * <ul>
 *   <li>normal —— 当前的主表(账号一行 + 凭证/积分/签到等列),跟"凭证列"功能配套</li>
 *   <li>packages —— 流量包平铺视图(一个账号多个流量包 → 多行,主键=accountId+packageCode)</li>
 * </ul>
 * 排序规则后续会按视图分别实现,先按原数据顺序展示
 */
type ViewMode = 'normal' | 'packages'
const viewMode = ref<ViewMode>('normal')
/** 视图模式下拉的可选项(Naive UI SelectOption[]) */
const viewModeOptions = [
  { label: '普通视图', value: 'normal' },
  { label: '流量包视图', value: 'packages' },
]

/**
 * 流量包视图的排序规则
 * <ul>
 *   <li>remainAsc —— cycleCapacityRemain 升序(余量最少的在顶,默认)</li>
 *   <li>remainDesc —— 降序</li>
 *   <li>endAsc —— cycleEndTime 升序(最先过期的在顶)</li>
 *   <li>endDesc —— 降序</li>
 * </ul>
 * 余额=0 的流量包(耗尽)不参与上述任何排序,统一丢到列表末尾
 */
type PackageSort = 'remainAsc' | 'remainDesc' | 'endAsc' | 'endDesc'
const packageSort = ref<PackageSort>('remainAsc')
const packageSortOptions = [
  { label: '余量递增', value: 'remainAsc' },
  { label: '余量递减', value: 'remainDesc' },
  { label: '到期时间递增', value: 'endAsc' },
  { label: '到期时间递减', value: 'endDesc' },
]

// ===== 签到按钮右键菜单（强制签到） =====
const showCheckinCtxMenu = ref(false)
const checkinCtxMenuX = ref(0)
const checkinCtxMenuY = ref(0)
const checkinCtxMenuOptions = [
  { label: '强制签到（忽略已签状态）', key: 'force-checkin' },
]

function onCheckinButtonContextMenu(e: MouseEvent) {
  e.preventDefault()
  showCheckinCtxMenu.value = false
  nextTick(() => {
    checkinCtxMenuX.value = e.clientX
    checkinCtxMenuY.value = e.clientY
    showCheckinCtxMenu.value = true
  })
}

function handleCheckinCtxMenuSelect(key: string) {
  showCheckinCtxMenu.value = false
  if (key === 'force-checkin') {
    forceCheckin()
  }
}

async function forceCheckin() {
  if (checkingIn.value) return
  const allIds = tableData.value.map((row) => row.id)
  if (allIds.length === 0) {
    message.info('没有账号可签到')
    return
  }
  checkingIn.value = true
  try {
    resetCheckinProgress(allIds)
    const res = await authFetch('/api/checkin/execute-stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accountIds: allIds, force: true }),
    })
    if (!res.ok) {
      message.error(`签到请求失败: ${res.status}`)
      return
    }
    const freshRows: AccountRow[] = []
    const summary = await consumeCheckinStream(res, freshRows)

    tableData.value
      .filter((row) => allIds.includes(row.id) && row.checkinProgress === 'running')
      .forEach((row) => { row.checkinProgress = 'error' })

    const parts: string[] = []
    if (summary.okCount > 0) parts.push(`成功 ${summary.okCount}`)
    if (summary.alreadyCount > 0) parts.push(`已签 ${summary.alreadyCount}`)
    if (summary.errCount > 0) parts.push(`失败 ${summary.errCount}`)
    message.success(`强制签到完成: ${parts.join(' / ') || '无变更'}`)

    if (freshRows.length > 0) {
      await refreshCreditForRows(freshRows)
    }
    clearFinishedCheckinProgress()
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    tableData.value
      .filter((row) => row.checkinProgress === 'running')
      .forEach((row) => { row.checkinProgress = 'error' })
    message.error(`签到异常: ${msg}`)
  } finally {
    checkingIn.value = false
  }
}

const accountStore = useAccountData()

/**
 * 点击"凭证"列 → 复制主凭证的完整原文到剪贴板
 * <p>
 * "主凭证" 跟表格里显示的缩写完全对齐：有 APIKey 就复制 APIKey，否则复制 AccessToken。
 * <ul>
 *   <li>主凭证缺失（apiKey / accessToken 都为空）→ 弹错误，不复制</li>
 *   <li>剪贴板 API 不可用（HTTP 非 secure context / 旧浏览器）→ 走 {@code document.execCommand('copy')} 兜底</li>
 *   <li>都失败 → 弹错误，提示用户手动复制（避免静默失败）</li>
 * </ul>
 */
async function copyPrimaryCredential(row: AccountRow): Promise<void> {
  const nickname = row.nickname && row.nickname !== '未定义' ? row.nickname : row.uid || '该账号'
  const apiKey = row.apiKey?.trim()
  const accessToken = row.accessToken?.trim()
  let plaintext = ''
  let label = ''
  if (apiKey) {
    plaintext = apiKey
    label = 'APIKey'
  } else if (accessToken) {
    plaintext = accessToken
    label = 'AccessToken'
  } else {
    message.error(`${nickname}：未找到可复制的凭证`)
    return
  }

  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(plaintext)
    } else {
      // 兜底：file:// 或 HTTP（非 https）下 navigator.clipboard 不可用
      const ta = document.createElement('textarea')
      ta.value = plaintext
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      if (!ok) throw new Error('execCommand copy failed')
    }
    message.success(`${nickname}：${label} 已复制`)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`复制失败：${msg}，请手动复制`)
  }
}

/**
 * 流量包视图用的扁平化行数据(已排序)
 * <p>
 * 展开策略:从 tableData 按 packages 展开成 PackageRow[],无 credit/packages 的账号不出现在流量包视图
 * <p>
 * 排序策略:
 * <ol>
 *   <li>余额=0(耗尽)的包不参与主排序 —— 统一丢到列表末尾,组内按 cycleEndTime 升序(让"先过期"在"后过期"前)</li>
 *   <li>非耗尽包按 packageSort 选中的规则排序:
 *       <ul>
 *         <li>remainAsc / remainDesc —— cycleCapacityRemain 数值升/降</li>
 *         <li>endAsc / endDesc —— cycleEndTime 字符串升/降("yyyy-MM-dd HH:mm:ss" 字典序=时间序)</li>
 *       </ul>
 *   </li>
 *   <li>cycleEndTime 为空的包在"按到期时间"排序时统一丢到组末(空串字典序最小,会冲顶部破坏语义)</li>
 * </ol>
 */
const packageRows = computed<PackageRow[]>(() => {
  const flat: PackageRow[] = []
  for (const acc of tableData.value) {
    const pkgs = acc.credit?.packages
    if (!Array.isArray(pkgs) || pkgs.length === 0) continue
    for (const pkg of pkgs) {
      flat.push({
        accountId: acc.id,
        uid: acc.uid,
        nickname: acc.nickname,
        package: pkg,
      })
    }
  }

  // 1) 分桶:耗尽 vs 非耗尽
  const exhausted: PackageRow[] = []
  const active: PackageRow[] = []
  for (const r of flat) {
    if (isExhausted(r.package)) exhausted.push(r)
    else active.push(r)
  }

  // 2) 非耗尽组按所选规则排序
  active.sort((a, b) => comparePackages(a.package, b.package, packageSort.value))

  // 3) 耗尽组固定按到期时间升序(让"先过期"在前)—— 不参与主排序
  exhausted.sort((a, b) => compareEndTimeAsc(a.package, b.package))

  return [...active, ...exhausted]
})

/**
 * 包比较器:按 PackageSort 规则比较两个 CreditPackage
 * <p>
 * 抽到模块级函数而不是 inline,让上面 sort 调用读起来不那么密
 */
function comparePackages(a: CreditPackage, b: CreditPackage, mode: PackageSort): number {
  switch (mode) {
    case 'remainAsc':
      return a.cycleCapacityRemain - b.cycleCapacityRemain
    case 'remainDesc':
      return b.cycleCapacityRemain - a.cycleCapacityRemain
    case 'endAsc':
      return compareEndTimeAsc(a, b)
    case 'endDesc':
      return compareEndTimeAsc(b, a) // 翻转参数实现降序
  }
}

/**
 * 到期时间升序比较
 * <p>
 * 空 cycleEndTime 一律丢到末尾(返回 1),不参与"最早过期在前"的语义
 */
function compareEndTimeAsc(a: CreditPackage, b: CreditPackage): number {
  const ta = a.cycleEndTime || ''
  const tb = b.cycleEndTime || ''
  if (!ta && !tb) return 0
  if (!ta) return 1
  if (!tb) return -1
  return ta.localeCompare(tb)
}

const checkinButtonLabel = computed(() => checkingIn.value ? '签到进行中…' : '一键每日签到')

/**
 * 需要提醒换新凭证的账号（已过期 或 剩余 ≤ 7 天）
 * <p>
 * 只统计「启用中」的账号 —— 已停用的账号不参与调用，过期也不影响服务，
 * 提醒它们只会制造噪音。
 * <p>
 * 无过期信息的账号（API Key、旧数据）不计入，否则会永远挂着一条无法消除的警告。
 */
const expiringAccounts = computed<AccountRow[]>(() =>
  tableData.value.filter((row) => {
    if (!row.enabled) return false
    const info = describeExpiry(row)
    return info.level === 'warn' || info.level === 'expired'
  })
)

const columns: DataTableColumns<AccountRow> = [
  { title: '编号', key: 'id', width: 70 },
 {
 title: '启用',
 key: 'enabled',
 width:60,
 render: (row: AccountRow): VNode => h(NSwitch, {
 value: row.enabled,
 size: 'small',
 loading: togglingEnabledIds.value.has(row.id),
 disabled: togglingEnabledIds.value.has(row.id),
 'onUpdate:value': (v: boolean) => toggleAccountEnabled(row, v),
 }),
 },
  {
    title: '昵称',
    key: 'nickname',
    width: 160,
    render: (row) => row.nickname || '未定义',
  },
  {
    title: '凭证',
    key: 'credentials',
    width: 180,
    render: (row: AccountRow) => h(NButton, {
      quaternary: true,
      size: 'tiny',
      style: { fontFamily: "'SFMono-Regular',Consolas,'Liberation Mono',monospace", fontSize: '12px' },
      onClick: () => { void copyPrimaryCredential(row) },
    }, () => row.primaryCredential || '-'),
  },
  {
    title: '凭证有效期',
    key: 'credentialExpiry',
    width: 110,
    render: (row: AccountRow) => {
      const info = describeExpiry(row)
      if (info.level === 'unknown') {
        return h('span', { style: { color: '#999' }, title: info.title }, info.text)
      }
      const COLORS: Record<string, string> = {
        ok: '#18a058',
        warn: '#f0a020',
        expired: '#d03050',
      }
      // 用原生 title 属性而非 NTooltip：tooltip 需要 n-config-provider 注入，
      // 在 NDataTable 的 render 函数里不可靠（控制台会报 injection not found）。
      // 原生 title 零依赖、必定生效，且对本场景（一行简短说明）完全够用。
      return h('span', {
        style: {
          color: COLORS[info.level],
          fontWeight: info.level === 'ok' ? '400' : '500',
          cursor: 'help',
        },
        title: info.title,
      }, info.text)
    },
  },
  {
    title: '积分剩余',
    key: 'creditRemain',
    width: 100,
    render: (row: AccountRow) => {
      if (!row.credit || !Array.isArray(row.credit.packages)) return '-'
      const remain = row.credit.packages.reduce((s, p) => s + (p.cycleCapacityRemain || 0), 0)
      const size = row.credit.packages.reduce((s, p) => s + (p.cycleCapacitySize || 0), 0)
      return `${remain.toFixed(1)} / ${size.toFixed(0)}`
    },
  },
  {
    title: '流量包明细',
    key: 'packages',
    width: 100,
    render: (row: AccountRow) => h(NButton, {
      quaternary: true,
      size: 'tiny',
      disabled: !row.credit || !Array.isArray(row.credit.packages) || row.credit.packages.length === 0,
      onClick: () => openPackagesModal(row),
    }, () => {
      const n = row.credit?.packages?.length ?? 0
      return n > 0 ? `查看明细 (${n})` : '-'
    }),
  },
  {
    title: '本期消耗',
    key: 'usage',
    width: 110,
    render: (row: AccountRow) => {
      if (row.apiKey && !row.accessToken) return '不提供显示'
      if (!row.usage) return '-'
      return `${row.usage.total.toFixed(2)}积分`
    },
  },
  {
    title: '当日签到状态',
    key: 'checkin',
    width: 110,
    render: (row: AccountRow) => {
      if (row.checkinProgress === 'running') {
        return h('span', { style: { color: '#2080f0', fontWeight: '500' } }, '签到中…')
      }
      if (row.checkinProgress === 'error') {
        return h('span', { style: { color: '#d03050', fontWeight: '500' } }, '签到失败')
      }
      if (!row.checkin) {
        return h('span', { style: { color: '#999' } }, '未知')
      }
      const __ckText = row.checkin.checkedIn ? '已签到' : '未签到'
      const __ckColor = row.checkin.checkedIn ? '#18a058' : '#f0a020'
      return h(NButton, {
        quaternary: true, size: 'tiny',
        style: { color: __ckColor, fontWeight: '500', padding: '0 4px' },
        onClick: () => openHistoryModal(row),
      }, () => __ckText)
    },
  },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 180,
    render: (row: AccountRow) => formatTime(row.updatedAt),
  },
  {
    title: '操作',
    key: 'actions',
    width: 70,
    fixed: 'right',
    render: (row: AccountRow): VNode => h(NButton, {
      size: 'tiny',
      quaternary: true,
      type: 'error',
      title: '删除账号(同步清理签到日志)',
      onClick: () => openDeleteModal(row),
    }, () => [h(Icon, { name: 'delete', size: 14 })]),
  },
]

/**
 * 流量包明细弹窗内嵌的小表格列
 * <p>
 * 严格按用户要求只展示三类信息:名称 / 积分余量 / 到期时间
 * <p>
 * cycleEndTime 是 CodeBuddy 上游直接回传的字符串(典型形态 "yyyy-MM-dd HH:mm:ss"),
 * 保持原样显示 —— 不在浏览器侧做时区转换,避免出现"看着是 7/31 但服务器认为已过期"这种鬼影
 * <p>
 * 余量为 0 的流量包(已用完)其名称与积分余量文本标红 —— 跟"签到失败"复用同一个 danger 色
 */
const packageColumns: DataTableColumns<CreditPackage> = [
  {
    title: '名称',
    key: 'packageName',
    ellipsis: { tooltip: true },
    render: (p: CreditPackage) => h('span', {
      style: { color: isExhausted(p) ? '#d03050' : undefined },
    }, p.packageName || p.packageCode || '-'),
  },
  {
    title: '积分余量',
    key: 'cycleCapacityRemain',
    width: 140,
    render: (p: CreditPackage) => h('span', {
      style: { color: isExhausted(p) ? '#d03050' : undefined },
    }, `${p.cycleCapacityRemain.toFixed(1)} / ${p.cycleCapacitySize.toFixed(0)}`),
  },
  {
    title: '到期时间',
    key: 'cycleEndTime',
    width: 170,
    render: (p: CreditPackage) => p.cycleEndTime || '-',
  },
]

/** 流量包是否已耗尽(余量 ≤ 0)。"用完"在产品语义上包含负数与 0 */
function isExhausted(p: CreditPackage): boolean {
  return (p.cycleCapacityRemain ?? 0) <= 0
}

/**
 * 流量包视图(主表)用的列定义
 * <p>
 * 5 列:编号 / 昵称 / 流量包名称 / 流量包余量 / 过期时间
 * <p>
 * 颜色复用 isExhausted,与弹窗里的 packageColumns 风格一致;
 * 排序规则待用户补,当前完全按 packageRows 顺序展示
 */
const packageViewColumns: DataTableColumns<PackageRow> = [
  {
    title: '编号',
    key: 'accountId',
    width: 70,
    render: (row: PackageRow) => row.accountId,
  },
  {
    title: '昵称',
    key: 'nickname',
    width: 160,
    render: (row: PackageRow) => row.nickname || '未定义',
  },
  {
    title: '流量包名称',
    key: 'packageName',
    ellipsis: { tooltip: true },
    render: (row: PackageRow) => h('span', {
      style: { color: isExhausted(row.package) ? '#d03050' : undefined },
    }, row.package.packageName || row.package.packageCode || '-'),
  },
  {
    title: '流量包余量',
    key: 'cycleCapacityRemain',
    width: 160,
    render: (row: PackageRow) => h('span', {
      style: { color: isExhausted(row.package) ? '#d03050' : undefined },
    }, `${row.package.cycleCapacityRemain.toFixed(1)} / ${row.package.cycleCapacitySize.toFixed(0)}`),
  },
  {
    title: '过期时间',
    key: 'cycleEndTime',
    width: 170,
    render: (row: PackageRow) => row.package.cycleEndTime || '-',
  },
]

/**
 * 弹窗用的"按余量+到期时间复合排序"的流量包副本
 * <p>
 * 排序策略(优先级从高到低):
 * <ol>
 *   <li>余量=0 的流量包丢到末尾 —— "最先过期放顶部"只对仍有积分的包有意义</li>
 *   <li>余量正常的包之间,按 cycleEndTime 字典序升序 —— "yyyy-MM-dd HH:mm:ss" 字典序就是时间序,无需 new Date() 解析</li>
 *   <li>到期时间为空的丢到末尾(空串字典序最小,会冲到最顶,违反"最先过期放顶部"语义)</li>
 * </ol>
 * <p>
 * 用 computed 包装,模态框打开/账号切换/credit 刷新都会自动重算 —— 不需要在 buildRows 里复制字段
 */
const sortedPackages = computed<CreditPackage[]>(() => {
  const list = packagesRow.value?.credit?.packages
  if (!Array.isArray(list)) return []
  return [...list].sort((a, b) => {
    // 优先级 1:余量=0 排末尾
    const ea = isExhausted(a) ? 1 : 0
    const eb = isExhausted(b) ? 1 : 0
    if (ea !== eb) return ea - eb

    // 优先级 2:到期时间升序
    const ta = a.cycleEndTime || ''
    const tb = b.cycleEndTime || ''
    if (!ta && !tb) return 0
    if (!ta) return 1
    if (!tb) return -1
    return ta.localeCompare(tb)
  })
})

function buildRows(): AccountRow[] {
  return accountStore.view.map((v: AccountDataView) => ({
    id: v.id,
    uid: v.uid,
    nickname: v.nickname,
    primaryCredential: pickPrimaryCredential(v.apiKey, v.accessToken),
    apiKey: v.accessToken ? null : v.apiKey,
    accessToken: v.accessToken,
     enabled: v.enabled,
    updatedAt: v.updatedAt,
    credentialExpiresAt: v.credentialExpiresAt,
    refreshExpiresAt: v.refreshExpiresAt,
    credit: v.credit ?? undefined,
    usage: v.usage ?? undefined,
    checkin: v.checkin ?? undefined,
    checkinProgress: undefined,
  }))
}

async function loadAccounts() {
  await accountStore.ensureAccountsLoaded()
  tableData.value = buildRows()
}

async function refreshExtras() {
  await accountStore.ensureExtrasLoaded()
  tableData.value = buildRows()
  message.success('积分、用量信息已刷新')
}

async function toggleAccountEnabled(row: AccountRow, newEnabled: boolean): Promise<void> {
 const oldEnabled = row.enabled
 row.enabled = newEnabled
 togglingEnabledIds.value.add(row.id)
 togglingEnabledIds.value = new Set(togglingEnabledIds.value)
 try {
 const res = await authFetch(`/api/accounts/${row.id}/enabled`, {
 method: 'PATCH',
 headers: { 'Content-Type': 'application/json' },
 body: JSON.stringify({ enabled: newEnabled }),
 })
 if (!res.ok) {
 const errBody = await res.json().catch(() => ({}))
 throw new Error(errBody?.message || `HTTP ${res.status}`)
 }
 const body = await res.json().catch(() => ({}))
 const savedEnabled = body?.data?.enabled
 if (typeof savedEnabled === 'boolean') {
 row.enabled = savedEnabled
 }
 accountStore.updateLocalAccountEnabled(row.id, row.enabled)
 message.success(`账号 #${row.id} 已${row.enabled ? '启用' : '停用'}`)
 } catch (e: unknown) {
 row.enabled = oldEnabled
 accountStore.updateLocalAccountEnabled(row.id, oldEnabled)
 const msg = e instanceof Error ? e.message : '未知错误'
 message.error(`切换账号启用状态失败: ${msg}`)
 } finally {
 togglingEnabledIds.value.delete(row.id)
 togglingEnabledIds.value = new Set(togglingEnabledIds.value)
 }
}

function pickPrimaryCredential(apiKey?: string | null, accessToken?: string | null): string {
  if (apiKey && apiKey.trim()) return `APIKey ${maskToken(apiKey)}`
  if (accessToken && accessToken.trim()) return `Access ${maskToken(accessToken)}`
  return '-'
}

function maskToken(token: string): string {
  if (!token) return '-'
  if (token.length <= 12) return token
  return token.slice(0, 6) + '…' + token.slice(-4)
}

function formatTime(ts: number | null | undefined): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

/** 临期告警阈值（天）—— 低于此值在列表里高亮，提醒及时换新凭证 */
const EXPIRY_WARN_DAYS = 7

/**
 * 凭证有效期的展示信息
 * <ul>
 *   <li>{@code level}: ok（> 7 天）/ warn（≤ 7 天）/ expired（已过期）/ unknown（无过期信息）</li>
 *   <li>{@code text}: 列表里显示的主文案</li>
 *   <li>{@code title}: tooltip 里的详细信息（含绝对时间与 refreshToken 情况）</li>
 * </ul>
 */
interface ExpiryInfo {
  level: 'ok' | 'warn' | 'expired' | 'unknown'
  text: string
  title: string
}

/**
 * 计算凭证有效期的展示信息
 * <p>
 * 为什么用"天"而不是精确到小时：accessToken 有效期约 55 天，
 * 精确到分钟没有实际意义，反而让列表变吵。临近过期（< 1 天）才切到小时粒度。
 */
function describeExpiry(row: AccountRow): ExpiryInfo {
  const expiresAt = row.credentialExpiresAt
  if (!expiresAt || expiresAt <= 0) {
    return {
      level: 'unknown',
      text: '-',
      title: row.apiKey
        ? 'API Key 账号无过期时间'
        : '该账号未记录有效期（旧版本导入或凭证不含 exp 字段）',
    }
  }

  const now = Date.now()
  const diffMs = expiresAt - now
  const absTime = formatTime(expiresAt)
  const refreshLine = row.refreshExpiresAt
    ? `\nrefreshToken 到期：${formatTime(row.refreshExpiresAt)}（剩余 ${Math.max(0, Math.floor((row.refreshExpiresAt - now) / 86400000))} 天）`
    : '\n无 refreshToken 记录'

  if (diffMs <= 0) {
    return {
      level: 'expired',
      text: '已过期',
      title: `凭证已于 ${absTime} 过期，该账号将无法调用上游，请重新扫码登录导入${refreshLine}`,
    }
  }

  const days = Math.floor(diffMs / 86400000)
  const hours = Math.floor(diffMs / 3600000)

  if (hours < 24) {
    return {
      level: 'warn',
      text: `剩 ${hours} 小时`,
      title: `凭证将于 ${absTime} 过期（不足 1 天），请尽快重新扫码登录${refreshLine}`,
    }
  }
  if (days <= EXPIRY_WARN_DAYS) {
    return {
      level: 'warn',
      text: `剩 ${days} 天`,
      title: `凭证将于 ${absTime} 过期（${days} 天后），建议尽快重新扫码登录导入${refreshLine}`,
    }
  }
  return {
    level: 'ok',
    text: `剩 ${days} 天`,
    title: `凭证将于 ${absTime} 过期${refreshLine}`,
  }
}

const allCheckedIn = computed(() => {
  if (tableData.value.length === 0) return false
  return tableData.value.every((row) => row.checkin?.checkedIn === true)
})

async function queryCheckinStatus() {
  await accountStore.ensureCheckinLoaded()
  tableData.value = buildRows()
}

async function oneClickCheckin() {
  if (checkingIn.value) return
  const targets = tableData.value
    .filter((row) => row.checkin?.checkedIn !== true)
    .map((row) => row.id)
  if (targets.length === 0) {
    message.info('所有账号今日已签到')
    return
  }

  checkingIn.value = true
  try {
    resetCheckinProgress(targets)
    const res = await authFetch('/api/checkin/execute-stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accountIds: targets }),
    })
    if (!res.ok) {
      message.error(`签到请求失败: ${res.status}`)
      return
    }

    const freshRows: AccountRow[] = []
    const summary = await consumeCheckinStream(res, freshRows)

    tableData.value
      .filter((row) => targets.includes(row.id) && row.checkinProgress === 'running')
      .forEach((row) => {
        row.checkinProgress = 'error'
      })

    const parts: string[] = []
    if (summary.okCount > 0) parts.push(`成功 ${summary.okCount}`)
    if (summary.alreadyCount > 0) parts.push(`已签 ${summary.alreadyCount}`)
    if (summary.errCount > 0) parts.push(`失败 ${summary.errCount}`)
    message.success(`签到完成: ${parts.join(' / ') || '无变更'}`)

    if (freshRows.length > 0) {
      await refreshCreditForRows(freshRows)
    }
    clearFinishedCheckinProgress()
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    tableData.value
      .filter((row) => row.checkinProgress === 'running')
      .forEach((row) => {
        row.checkinProgress = 'error'
      })
    message.error(`签到异常: ${msg}`)
  } finally {
    checkingIn.value = false
  }
}

function resetCheckinProgress(targetIds: number[]): void {
  tableData.value.forEach((row) => {
    if (targetIds.includes(row.id)) {
      row.checkinProgress = 'pending'
    }
  })
}

function clearFinishedCheckinProgress(): void {
  tableData.value.forEach((row) => {
    if (row.checkinProgress === 'done') {
      row.checkinProgress = undefined
    }
  })
}

async function consumeCheckinStream(
  res: Response,
  freshRows: AccountRow[]
): Promise<{ okCount: number, alreadyCount: number, errCount: number }> {
  if (!res.body) {
    throw new Error('签到流响应为空')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let summary = { okCount: 0, alreadyCount: 0, errCount: 0 }

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''

    for (const rawLine of lines) {
      const line = rawLine.trim()
      if (!line) continue
      summary = handleCheckinStreamEvent(line, freshRows, summary)
    }

    if (done) {
      const tail = buffer.trim()
      if (tail) {
        summary = handleCheckinStreamEvent(tail, freshRows, summary)
      }
      break
    }
  }

  return summary
}

function handleCheckinStreamEvent(
  raw: string,
  freshRows: AccountRow[],
  summary: { okCount: number, alreadyCount: number, errCount: number }
): { okCount: number, alreadyCount: number, errCount: number } {
  const evt = JSON.parse(raw) as CheckinStreamEvent
  if (evt.type === 'started') {
    applyCheckinStarted(evt)
    return summary
  }
  if (evt.type === 'result') {
    applyCheckinResult(evt, freshRows)
    return summary
  }
  return {
    okCount: evt.okCount ?? 0,
    alreadyCount: evt.alreadyCount ?? 0,
    errCount: evt.errCount ?? 0,
  }
}

function applyCheckinStarted(evt: CheckinStreamEvent): void {
  const row = tableData.value.find((item) => item.id === evt.accountId)
  if (!row) return
  row.checkinProgress = 'running'
}

function applyCheckinResult(evt: CheckinStreamEvent, freshRows: AccountRow[]): void {
  const row = tableData.value.find((item) => item.id === evt.accountId)
  if (!row) return

  const isOk = evt.status === 'checked_in' || evt.status === 'already_checked_in'
  if (isOk) {
    const snap: CheckinSnapshot = {
      checkedIn: true,
      checkinTime: evt.checkinTime ?? Date.now(),
      checkinType: 'daily',
      fetchedAt: Date.now(),
    }
    row.checkin = snap
    row.checkinProgress = 'done'
    if (row.uid && row.uid !== '-') {
      accountStore.setLocalCheckin(row.uid, snap)
    }
    if (evt.status === 'checked_in') {
      freshRows.push(row)
    }
    return
  }

  row.checkinProgress = 'error'
  message.warning(`账号 ${row.nickname || evt.uid || '-'} 签到失败: ${evt.message || '未知错误'}`)
}

async function refreshCreditForRows(rows: AccountRow[]) {
  if (rows.length === 0) return
  await Promise.allSettled(rows.map((row) => accountStore.refreshOne(row.uid)))
  tableData.value = buildRows()
}

function openChooser() {
  showChooser.value = true
  detailInfo.value = null
  apiKeyForm.value = { nickname: '', apiKey: '' }
}

async function loadFromLocal() {
  showChooser.value = false
  showDetail.value = true
  loadingDetail.value = true
  await queryCheckinStatus()
  detailInfo.value = null
  showAccessToken.value = false
  try {
    const res = await authFetch('/api/workbuddy-info')
    if (!res.ok) throw new Error(`请求失败: ${res.status}`)
    detailInfo.value = await res.json()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`获取本机信息失败: ${msg}`)
    showDetail.value = false
  } finally {
    loadingDetail.value = false
  }
}

async function handleFileUpload({ file }: { file: UploadFileInfo }) {
  if (!file.file) return false
  showChooser.value = false
  showDetail.value = true
  loadingDetail.value = true
  detailInfo.value = null
  showAccessToken.value = false
  try {
    const form = new FormData()
    form.append('file', file.file)
    const res = await authFetch('/api/import-info', { method: 'POST', body: form })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `请求失败: ${res.status}`)
    }
    detailInfo.value = await res.json()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`文件导入失败: ${msg}`)
    showDetail.value = false
  } finally {
    loadingDetail.value = false
  }
  return false
}

function openApiKeyForm() {
  showChooser.value = false
  apiKeyForm.value = { nickname: '', apiKey: '' }
  showApiKeyForm.value = true
}

async function saveApiKeyOnly() {
  if (!apiKeyForm.value.apiKey.trim()) {
    message.warning('请输入 API Key')
    return
  }
  savingApiKey.value = true
  try {
    const payload = {
      account: { nickname: apiKeyForm.value.nickname.trim() || undefined },
      apiKey: apiKeyForm.value.apiKey.trim(),
    }
    const res = await authFetch('/api/accounts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const body = await res.json().catch(() => ({}))
    if (res.ok) {
      const status = body.status as string
      const text = status === 'created' ? '新建成功' : status === 'updated' ? '已覆盖旧记录' : '保存成功'
      message.success(text)
      showApiKeyForm.value = false
      await loadAccounts()
    } else if (res.status === 409) {
      message.warning('该 API Key已存在，未发生变化，无需重复保存')
    } else {
      const msg = body?.message || `请求失败: ${res.status}`
      message.error(`保存失败: ${msg}`)
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`保存失败: ${msg}`)
  } finally {
    savingApiKey.value = false
  }
}

function cancelDetail() {
  showDetail.value = false
  detailInfo.value = null
}

async function saveDetail() {
  if (!detailInfo.value) return
  savingDetail.value = true
  try {
    const res = await authFetch('/api/accounts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(detailInfo.value),
    })
    const body = await res.json().catch(() => ({}))
    if (res.ok) {
      const status = body.status as string
      const text = status === 'created' ? '新建成功' : status === 'updated' ? '已覆盖旧记录' : '保存成功'
      message.success(text)
      showDetail.value = false
      detailInfo.value = null
      await loadAccounts()
    } else if (res.status === 409) {
      message.warning('该账户信息已存在且未发生变化，无需重复保存')
    } else {
      const msg = body?.message || `请求失败: ${res.status}`
      message.error(`保存失败: ${msg}`)
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`保存失败: ${msg}`)
  } finally {
    savingDetail.value = false
  }
}

onMounted(async () => {
  await loadAccounts()
  await refreshExtras()
  await queryCheckinStatus()
})

// 组件卸载时清理扫码登录的定时器，避免泄漏（尤其是轮询会一直发请求）
onUnmounted(() => {
  stopScanCountdown()
  stopScanPolling()
})

// ============== 签到历史日志模态框 ==============

/**
 * 单条签到历史日志条目（与后端 CheckinLogItem 字段对齐）
 * <p>
 * extra 字段为 Object（通常是 Map），便于直接渲染
 */
interface CheckinLogItem {
  id: number
  accountId: number
  checkinType: string
  checkinTime: number
  extra: Record<string, unknown> | string | null
}

/**
 * 历史日志模态框状态
 * <p>
 * 设计原则:历史日志是"只读视图",不修改表格行的任何状态。
 * 翻页/排序在模态框内独立管理,与签到主流程解耦。
 */
const showHistoryModal = ref(false)
const historyAccountId = ref<number | null>(null)
const historyAccountName = ref('')
const historyList = ref<CheckinLogItem[]>([])
const historyTotal = ref(0)
const historyPages = ref(0)
const historyPageNum = ref(1)
const historyPageSize = ref(10)
const historyOrderBy = ref('checkin_time')
const historyAsc = ref(false)
const historyLoading = ref(false)
const historyError = ref<string | null>(null)

/**
 * 删除确认模态框状态
 * <p>
 * 设计原则:删除是不可逆操作,必须明确展示目标账号标识(uid + 昵称) + 影响范围(签到日志级联清理)
 */
const showDeleteModal = ref(false)
const deleteTarget = ref<AccountRow | null>(null)
const deleting = ref(false)

/**
 * 历史模态框分页列定义
 * <p>
 * id / 类型 / 签到时间(格式化) / 扩展信息(展开查看)
 */
const historyColumns: DataTableColumns<CheckinLogItem> = [
  { title: '编号', key: 'id', width: 70 },
  { title: '类型', key: 'checkinType', width: 90 },
  {
    title: '签到时间',
    key: 'checkinTime',
    width: 180,
    render: (row: CheckinLogItem) => formatTime(row.checkinTime),
  },
  {
    title: '扩展信息',
    key: 'extra',
    ellipsis: { tooltip: true },
    render: (row: CheckinLogItem) => {
      if (row.extra == null) return '-'
      if (typeof row.extra === 'string') return row.extra
      try {
        return JSON.stringify(row.extra)
      } catch {
        return String(row.extra)
      }
    },
  },
]

/**
 * 打开历史日志模态框
 * <p>
 * 入口:点击表格"当日签到状态"列
 */
function openHistoryModal(row: AccountRow): void {
  historyAccountId.value = row.id
  historyAccountName.value = row.nickname || row.uid || `账号 #${row.id}`
  historyPageNum.value = 1
  showHistoryModal.value = true
  void loadHistory()
}

/**
 * 关闭模态框
 */
function closeHistoryModal(): void {
  showHistoryModal.value = false
  historyList.value = []
  historyError.value = null
}

/**
 * 打开删除确认模态框
 * <p>
 * 入口:账号表格"操作"列的删除按钮
 */
function openDeleteModal(row: AccountRow): void {
  deleteTarget.value = row
  showDeleteModal.value = true
}

/**
 * 取消删除
 */
function cancelDelete(): void {
  showDeleteModal.value = false
  deleteTarget.value = null
}

/**
 * 确认删除 —— 调后端 DELETE /api/accounts/{id}
 * <p>
 * 行为:
 * <ul>
 *   <li>乐观更新:成功后立即从 store / localStorage 移除该账号,无需重新拉列表</li>
 *   <li>失败:提示错误,保留表格行(用户可重试)</li>
 *   <li>关联清理由后端 FK 处理(签到日志 CASCADE,下游 key SET NULL)</li>
 * </ul>
 */
async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    const target = deleteTarget.value
    const res = await authFetch(`/api/accounts/${target.id}`, { method: 'DELETE' })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `HTTP ${res.status}`)
    }
    // 乐观更新:从 store 立即移除
    accountStore.removeLocalAccount(target.id)
    message.success(`已删除账号 #${target.id} (${target.nickname || target.uid || ''})`)
    cancelDelete()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`删除失败: ${msg}`)
  } finally {
    deleting.value = false
  }
}

/**
 * 拉取一页历史日志
 */
async function loadHistory(): Promise<void> {
  if (historyAccountId.value == null) return
  historyLoading.value = true
  historyError.value = null
  try {
    const res = await authFetch('/api/checkin/history', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accountId: historyAccountId.value,
        pageNum: historyPageNum.value,
        pageSize: historyPageSize.value,
        orderBy: historyOrderBy.value,
        asc: historyAsc.value,
      }),
    })
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`)
    }
    const body = await res.json()
    const page = body?.data
    if (!page) {
      throw new Error('响应格式异常:缺少 data 字段')
    }
    historyList.value = Array.isArray(page.list) ? page.list : []
    historyTotal.value = Number(page.total ?? 0)
    historyPages.value = Number(page.pages ?? 0)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    historyError.value = `加载历史失败: ${msg}`
    historyList.value = []
    historyTotal.value = 0
    historyPages.value = 0
  } finally {
    historyLoading.value = false
  }
}

/**
 * 翻页/排序变化
 */
function onHistoryPageChange(p: number): void {
  historyPageNum.value = p
  void loadHistory()
}

function onHistoryPageSizeChange(s: number): void {
  historyPageSize.value = s
  historyPageNum.value = 1
  void loadHistory()
}

function onHistorySortChange(value: { orderBy: string, asc: boolean }): void {
  historyOrderBy.value = value.orderBy
  historyAsc.value = value.asc
  historyPageNum.value = 1
  void loadHistory()
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="toolbar-left">
        <n-select v-model:value="viewMode" :options="viewModeOptions" size="small" style="width:140px" />
        <n-select v-if="viewMode === 'packages'" v-model:value="packageSort" :options="packageSortOptions" size="small"
          style="width:140px" />
      </div>
      <div class="toolbar-right">
        <n-button quaternary @click="refreshExtras">
          <template #icon>
            <Icon name="refresh" :size="14" />
          </template>
          刷新
        </n-button>
        <template v-if="viewMode === 'normal'">
          <n-button type="warning" :disabled="allCheckedIn || checkingIn" :loading="checkingIn" @click="oneClickCheckin"
            @contextmenu="onCheckinButtonContextMenu">
            {{ checkinButtonLabel }}
          </n-button>
          <n-dropdown trigger="manual" placement="bottom-start" :show="showCheckinCtxMenu"
            :options="checkinCtxMenuOptions" :x="checkinCtxMenuX" :y="checkinCtxMenuY"
            @select="handleCheckinCtxMenuSelect" @clickoutside="showCheckinCtxMenu = false" />
        </template>
        <n-button type="primary" @click="openChooser">
          <template #icon>
            <Icon name="plus" :size="14" />
          </template>
          添加账户
        </n-button>
      </div>
    </div>

    <!--
      临期/过期凭证汇总提示
      <p>
      只在「有需要处理的账号」时出现 —— 平时不占视觉空间。
      accessToken 有效期约 55 天，不提醒很容易忘记，直到某天账号静默失效。
      点击可让用户立刻去重新扫码（跳转到添加账户弹窗）。
    -->
    <n-alert v-if="expiringAccounts.length > 0" type="warning" :show-icon="true" class="expiry-alert">
      <div class="expiry-alert-body">
        <span>
          有 <b>{{ expiringAccounts.length }}</b> 个账号的凭证即将过期或已过期：
          {{ expiringAccounts.map((r) => r.nickname || r.uid).join('、') }}
        </span>
        <n-button size="small" type="warning" @click="openChooser">去重新获取</n-button>
      </div>
    </n-alert>

    <n-card :bordered="false" class="table-card">
      <!--
        在两个 n-data-table 上分别加 :key,key 值含 viewMode
        目的:切换视图时强制 n-data-table 组件实例整体重建,避免 Vue 复用节点
        导致旧视图的内部状态(行缓存/虚拟滚动位置/排序)与新视图的 data/columns 不一致,
        表现就是"切换排序时列表叠加/不刷新"
        key 用 'normal' / 'packages' 区别两个分支(不冲突 v-if/else unique-key 规则)
      -->
      <n-data-table v-if="viewMode === 'normal'" key="normal" :columns="columns" :data="tableData" :bordered="false"
        :single-line="false" size="small" :row-key="(row: AccountRow) => row.id" />
      <n-data-table v-else key="packages" :columns="packageViewColumns" :data="packageRows" :bordered="false"
        :single-line="false" size="small"
        :row-key="(row: PackageRow) => `${row.accountId}-${row.package.packageCode || 'empty'}-${row.package.cycleEndTime || '0'}`" />
    </n-card>

    <!--添加账户入口 -->
    <n-modal v-model:show="showChooser" preset="card" title="添加账户" style="width:520px;">
      <div class="entry-list">
        <button class="entry-item entry-item--recommended" type="button" @click="openScanLogin">
          <div class="entry-icon">
            <Icon name="qrcode" :size="20" />
          </div>
          <div class="entry-body">
            <div class="entry-title">
              扫码登录
              <span class="entry-tag">推荐</span>
            </div>
            <div class="entry-desc">用手机微信扫码登录 CodeBuddy，凭证由服务端自动领取导入（最简单可靠）</div>
          </div>
        </button>
        <button class="entry-item" type="button" @click="loadFromLocal">
          <div class="entry-icon">
            <Icon name="logo" :size="20" />
          </div>
          <div class="entry-body">
            <div class="entry-title">本机获取</div>
            <div class="entry-desc">读取本机 CodeBuddy已登录的账户信息</div>
          </div>
        </button>
        <n-upload accept=".info,application/json" :show-file-list="false" @before-upload="handleFileUpload">
          <button class="entry-item" type="button">
            <div class="entry-icon">
              <Icon name="refresh" :size="20" />
            </div>
            <div class="entry-body">
              <div class="entry-title">文件导入</div>
              <div class="entry-desc">选择一个 workbuddy-desktop.info文件</div>
            </div>
          </button>
        </n-upload>
        <button class="entry-item" type="button" @click="openApiKeyForm">
          <div class="entry-icon">
            <Icon name="settings" :size="20" />
          </div>
          <div class="entry-body">
            <div class="entry-title">手动输入 API Key</div>
            <div class="entry-desc">仅填写昵称与 API Key创建账户</div>
          </div>
        </button>
      </div>
    </n-modal>

    <!--本机/文件详情 -->
    <n-modal v-model:show="showDetail" preset="card" title="账户详情" style="width:640px;">
      <n-spin :show="loadingDetail">
        <template v-if="detailInfo">
          <n-card title="账户信息" size="small" :bordered="false" class="info-card">
            <n-descriptions label-placement="left" bordered :column="2" size="small">
              <n-descriptions-item label="昵称">{{ detailInfo.account?.nickname || '未定义' }}</n-descriptions-item>
              <n-descriptions-item label="UID">{{ detailInfo.account?.uid || '-' }}</n-descriptions-item>
              <n-descriptions-item label="UIN">{{ detailInfo.account?.uin || '-' }}</n-descriptions-item>
              <n-descriptions-item label="类型">{{ detailInfo.account?.type || '-' }}</n-descriptions-item>
              <n-descriptions-item label="手机号">{{ detailInfo.account?.phoneNumber || '-' }}</n-descriptions-item>
              <n-descriptions-item label="插件启用">{{ detailInfo.account?.pluginEnabled ? '是' : '否'
              }}</n-descriptions-item>
            </n-descriptions>
          </n-card>
          <n-card title="凭证信息" size="small" :bordered="false" class="info-card">
            <n-descriptions label-placement="left" bordered :column="1" size="small">
              <n-descriptions-item label="凭证类型">{{ detailInfo.auth?.accessToken ? 'AccessToken (JWT)' : '-'
              }}</n-descriptions-item>
              <n-descriptions-item label="Access Token">
                <span class="token-cell">
                  <code>{{ showAccessToken ? detailInfo.auth?.accessToken : maskToken(detailInfo.auth?.accessToken || '') }}</code>
                  <button v-if="detailInfo.auth?.accessToken" class="eye-btn" type="button"
                    @click="showAccessToken = !showAccessToken">
                    <Icon :name="showAccessToken ? 'eye-off' : 'eye'" :size="14" />
                  </button>
                </span>
              </n-descriptions-item>
              <n-descriptions-item label="域名">{{ detailInfo.auth?.domain || '-' }}</n-descriptions-item>
              <n-descriptions-item label="作用域">{{ detailInfo.auth?.scope || '-' }}</n-descriptions-item>
              <n-descriptions-item label="Token过期">{{ formatTime(detailInfo.auth?.expiresAt) }}</n-descriptions-item>
            </n-descriptions>
          </n-card>
        </template>
      </n-spin>
      <template #footer>
        <n-space justify="end">
          <n-button @click="cancelDetail" :disabled="savingDetail">取消</n-button>
          <n-button type="primary" :loading="savingDetail" :disabled="!detailInfo || loadingDetail"
            @click="saveDetail">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!--手动输入 API Key -->
    <n-modal v-model:show="showApiKeyForm" preset="card" title="手动输入 API Key" style="width:480px;">
      <n-form-item label="昵称（可选）" label-placement="top" :show-feedback="false">
        <n-input v-model:value="apiKeyForm.nickname" placeholder="留空则不设置昵称" clearable />
      </n-form-item>
      <n-form-item label="API Key" label-placement="top" :show-feedback="false" required>
        <n-input v-model:value="apiKeyForm.apiKey" placeholder="请输入 API Key" type="password" show-password-on="click"
          clearable />
      </n-form-item>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showApiKeyForm = false" :disabled="savingApiKey">取消</n-button>
          <n-button type="primary" :loading="savingApiKey" @click="saveApiKeyOnly">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!--
      扫码登录（插件授权新链路，2026-10）
      <p>
      凭证由服务端向上游领取并落库，不经浏览器、无需书签；
      登录链接是官方地址（后端已校验 host 为 copilot.tencent.com），
      因此不再需要旧版的长篇风险告知弹窗。
    -->
    <n-modal v-model:show="showScanLogin" preset="card" title="扫码登录添加账户" style="width:640px;"
      :mask-closable="false" @close="closeScanLogin">
      <n-spin :show="creatingSession">
        <template v-if="scanSession">
          <n-alert type="info" :show-icon="true" style="margin-bottom:14px;">
            <div style="line-height:1.75;">
              <b>操作步骤</b>
              <div><b>① 点「打开登录页」</b>，用手机微信扫描页面上的二维码完成登录</div>
              <div><b>② 保持本弹窗开启</b> → 服务端自动检测登录完成并导入凭证，本弹窗随即自动关闭</div>
              <div style="margin-top:6px; font-size:12px; color:#8c8c8c;">
                凭证由服务端直接从官方接口领取（不经浏览器）；
                登录链接为官方地址 copilot.tencent.com，请勿使用他人发来的链接
              </div>
            </div>
          </n-alert>

          <n-form-item label="登录链接" label-placement="top" :show-feedback="false">
            <n-input :value="scanSession.loginUrl" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" readonly
              @focus="($event: FocusEvent) => ($event.target as HTMLTextAreaElement)?.select()" />
          </n-form-item>

          <n-space style="margin-bottom:14px;">
            <n-button type="primary" @click="openLoginUrl">
              <template #icon>
                <Icon name="logo" :size="16" />
              </template>
              打开登录页
            </n-button>
            <n-button @click="copyLoginUrl">
              <template #icon>
                <Icon name="copy" :size="16" />
              </template>
              复制链接
            </n-button>
            <n-tag :type="scanRemaining > 60 ? 'success' : 'warning'" size="medium">
              会话剩余 {{ scanRemainingText }}
            </n-tag>
          </n-space>

          <n-alert v-if="scanRemaining <= 0" type="warning" :show-icon="true" style="margin-bottom:14px;">
            登录会话已过期，请关闭后重新点击「扫码登录」生成新的链接。
          </n-alert>
          <n-text v-else depth="3" style="font-size:13px;">
            正在等待扫码登录…（每 3 秒检测一次，完成后自动导入并关闭）
          </n-text>
        </template>
        <n-text v-else depth="3" style="font-size:13px;">
          正在创建登录会话…
        </n-text>
      </n-spin>

      <template #footer>
        <n-space justify="end">
          <n-button @click="closeScanLogin">关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <!--流量包明细 -->
    <n-modal v-model:show="showPackages" preset="card"
      :title="`流量包明细 — ${packagesRow?.nickname && packagesRow.nickname !== '未定义' ? packagesRow.nickname : packagesRow?.uid || ''}`"
      style="width:680px; max-height: calc(100vh -64px);">
      <div
        v-if="!packagesRow?.credit || !Array.isArray(packagesRow.credit.packages) || packagesRow.credit.packages.length === 0"
        class="empty-hint">
        暂无流量包数据。请点击「刷新积分」拉取一次。
      </div>
      <div v-else class="packages-table-scroll">
        <n-data-table size="small" :bordered="false" :single-line="false" :columns="packageColumns"
          :data="sortedPackages" :row-key="(p: CreditPackage) => p.packageCode" />
      </div>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showPackages = false">关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 签到历史日志模态框（点击"当日签到状态"列触发） -->
    <n-modal v-model:show="showHistoryModal" preset="card" :title="`签到历史 · ${historyAccountName}`" style="width: 880px;"
      :on-after-leave="closeHistoryModal">
      <!-- 排序工具栏：表格上方靠左 -->
      <n-space align="center" size="small" style="margin-bottom: 8px;">
        <span class="history-summary">排序：</span>
        <n-button size="tiny" :type="historyOrderBy === 'checkin_time' && !historyAsc ? 'primary' : 'default'"
          @click="onHistorySortChange({ orderBy: 'checkin_time', asc: false })">时间倒序</n-button>
        <n-button size="tiny" :type="historyOrderBy === 'checkin_time' && historyAsc ? 'primary' : 'default'"
          @click="onHistorySortChange({ orderBy: 'checkin_time', asc: true })">时间正序</n-button>
        <n-button size="tiny" :type="historyOrderBy === 'id' ? 'primary' : 'default'"
          @click="onHistorySortChange({ orderBy: 'id', asc: false })">按 ID</n-button>
      </n-space>
      <n-data-table :columns="historyColumns" :data="historyList" :loading="historyLoading" :bordered="false"
        :single-line="false" size="small" :row-key="(row: CheckinLogItem) => row.id" />
      <div v-if="historyError" class="history-error">{{ historyError }}</div>
      <div v-else-if="!historyLoading && historyList.length === 0" class="history-empty">暂无签到记录</div>
      <n-space justify="space-between" align="center" style="margin-top: 12px;">
        <span class="history-summary">共 {{ historyTotal }} 条 · 第 {{ historyPageNum }} / {{ historyPages || 1 }} 页</span>
        <n-pagination :page="historyPageNum" :page-size="historyPageSize" :item-count="historyTotal"
          :page-sizes="[10, 20, 50, 100]" show-size-picker @update:page="onHistoryPageChange"
          @update:page-size="onHistoryPageSizeChange" />
      </n-space>
      <template #footer>
        <n-space justify="end">
          <n-button @click="closeHistoryModal">关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 删除账号确认模态框 -->
    <n-modal v-model:show="showDeleteModal" preset="card" :title="`删除账号 #${deleteTarget?.id ?? ''}`"
      style="width: 460px;">
      <n-space vertical size="medium">
        <p>确定删除账号 <strong>{{ deleteTarget?.nickname || deleteTarget?.uid || `#${deleteTarget?.id}` }}</strong> 吗?</p>
        <p style="color: #d03050; font-size: 12px; margin: 0;">
          此操作不可恢复。删除后:
        </p>
        <ul style="color: #d03050; font-size: 12px; margin: 0; padding-left: 20px;">
          <li>该账号的所有签到日志将被级联清理</li>
          <li>指定了该账号的下游 Key 的"指定账号"字段会被置空(designated 模式下次调用将被业务校验拒绝)</li>
          <li>本地缓存的积分 / 用量 / 签到快照将被清理</li>
        </ul>
      </n-space>
      <template #footer>
        <n-space justify="end">
          <n-button @click="cancelDelete">取消</n-button>
          <n-button type="error" :loading="deleting" @click="confirmDelete">确认删除</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
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

/* 临期凭证汇总条：横幅式，但右侧按钮与文字在同一行（默认 alert 会撑高） */
.expiry-alert-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  line-height: 1.7;
}

.table-card {
  background: #fff;
  border-radius: 8px;
}

.empty-hint {
  padding: 24px 12px;
  text-align: center;
  color: #8c8c8c;
  font-size: 13px;
}

/**
 * 流量包明细弹窗内嵌表格的滚动容器
 * <p>
 * 双重防御保证弹窗"不超出显示高度":
 * <ol>
 *   <li>外层 n-modal 已用 max-height: calc(100vh - 64px) 限制整体高度</li>
 *   <li>本容器用 calc(100vh - 240px) 给内嵌表格一个明确的上限,长流量包列表在弹窗体内独立滚动
 *       —— footer 始终固定在弹窗底部,不会因列表变长而被顶到屏幕外</li>
 * </ol>
 * 240px = title(36) + padding(40) + footer(60) + 上下空隙(104) 的余量估值
 */
.packages-table-scroll {
  max-height: calc(100vh - 240px);
  overflow: auto;
}

.entry-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.entry-list :deep(.n-upload) {
  display: block;
  width: 100%;
}

.entry-list :deep(.n-upload-trigger) {
  display: block;
  width: 100%;
}

.entry-item {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  text-align: left;
  background: #fafbfc;
  border: 1px solid #ececf0;
  border-radius: 8px;
  padding: 14px 16px;
  cursor: pointer;
  font-family: inherit;
  transition: background .12s ease, border-color .12s ease;
}

.entry-item:hover {
  background: #f2f3f5;
  border-color: #d9dadd;
}

/* 推荐入口（扫码登录）—— 用主题色描边与高亮图标，视觉上引导优先选它 */
.entry-item--recommended {
  background: #f0f9ff;
  border-color: #7ec8f5;
}

.entry-item--recommended:hover {
  background: #e3f4fd;
  border-color: #4ab3ef;
}

.entry-item--recommended .entry-icon {
  background: #1a7fbf;
}

.entry-item--recommended .entry-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.entry-tag {
  font-size: 11px;
  font-weight: 400;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 3px;
  background: #1a7fbf;
  color: #fff;
}

.entry-icon {
  width: 36px;
  height: 36px;
  border-radius: 6px;
  background: #1f2329;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.entry-body {
  flex: 1;
  min-width: 0;
}

.entry-title {
  font-size: 14px;
  font-weight: 500;
  color: #1f2329;
  margin-bottom: 2px;
}

.entry-desc {
  font-size: 12px;
  color: #8c8c8c;
}

.info-card {
  background: #fafbfc;
  border-radius: 6px;
  margin-bottom: 12px;
}

.info-card:last-child {
  margin-bottom: 0;
}

.token-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  word-break: break-all;
}

.eye-btn {
  background: transparent;
  border: 1px solid #ececf0;
  border-radius: 4px;
  padding: 2px 4px;
  cursor: pointer;
  color: #4e5969;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.eye-btn:hover {
  background: #f2f3f5;
}

/* 签到历史日志模态框内的辅助样式 */
.history-error {
  color: #d03050;
  padding: 8px 0;
  text-align: center;
}

.history-empty {
  color: #999;
  padding: 24px 0;
  text-align: center;
}

.history-summary {
  color: #666;
  font-size: 12px;
}
</style>
