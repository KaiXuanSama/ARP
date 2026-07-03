/**
 * 账号数据全局 store（composable）
 * <p>
 * 设计目标:把"先读 localStorage 缓存,后端响应回来再写回"这套流程集中管理,
 * 让任何组件(Settings / AccountManagement / 未来的 Overview / Chat 面板)都能
 * 拿到同步的账号 / 积分 / 签到数据,**不需要先去 AccountManagement 跑一遍**。
 * <p>
 * 核心策略:
 * <ol>
 *   <li>所有数据存 localStorage(agentreproxy.accounts / .account_extras / .account_checkin)</li>
 *   <li>组件读数:全部走 store 的 ref,Vue 自动响应式更新</li>
 *   <li>组件触发拉取:ensureXxxLoaded() —— 缓存非空立刻返回;否则阻塞等待;后台总会在拿到响应后更新 ref</li>
 *   <li>并发去重:in-flight Promise 缓存避免多个组件同时拉同一个端点</li>
 *   <li>跨 tab 同步:监听 'storage' 事件,localStorage 被另一 tab 改了就重新读</li>
 * </ol>
 * <p>
 * 不引入新依赖(没用 Pinia / Vuex),整个 store 就是个模块级单例 + 一组 ref。
 * <p>
 * localStorage 工具(读 / 写 / 合并)继续走 {@code utils/accountExtras.ts}。
 * 本 store 只负责"什么时候拉、什么时候更新 ref、并发去重、跨 tab 同步"。
 */
import { ref, type Ref } from 'vue'
import {
  getCachedAccounts,
  setCachedAccounts,
  getAccountExtra,
  mergeAccountExtra,
  getCheckinSnapshot,
  setCheckinSnapshot,
  isCheckinSnapshotFromToday,
  type CachedAccount,
  type CreditSnapshot,
  type UsageSnapshot,
  type CheckinSnapshot,
} from '../utils/accountExtras'

// ============== 类型 ==============

/**
 * 单个账号的扩展数据视图（响应式）
 * <p>
 * 把 CachedAccount + credit/usage/checkin 三个独立 localStorage 字段组合成一个对象,
 * 组件可以直接 v-for 渲染一行
 */
export interface AccountDataView {
  id: number
  uid: string
  nickname: string
  accountJson: string
  accessToken: string | null
  apiKey: string | null
  enabled: boolean
  updatedAt: number
  credit: CreditSnapshot | null
  usage: UsageSnapshot | null
  checkin: CheckinSnapshot | null
}

// ============== 响应式 state ==============

/** 账号列表（直接来自 localStorage,组件 watch 即可） */
const accounts = ref<CachedAccount[]>([])

/**
 * extras/checkin 的响应式版本号
 * <p>
 * 核心目的:credit / usage / checkin 真正落在 localStorage 里,不是 Vue ref。
 * 如果只写 localStorage 不 bump 一个 reactive token,依赖 buildView() 的组件不会自动重算,
 * 就会出现"第一次打开 Settings 只显示账号列表,第二次刷新才看到积分"的问题。
 * <p>
 * 任何一次 mergeAccountExtra(...) / setCheckinSnapshot(...) 成功后都把对应版本号 +1,
 * buildView() 里的 getter 通过读取这些 ref 建立依赖,从而触发重渲染。
 */
const extrasVersion = ref(0)
const checkinVersion = ref(0)

/**
 * 触发"读一次 localStorage 并把结果同步进 accounts.value"
 * <p>
 * 在 store 初始化、跨 tab 'storage' 事件、ensureAccountsLoaded() 后端响应回来时调用
 */
function reloadAccountsFromStorage(): void {
  accounts.value = getCachedAccounts()
}

// ============== in-flight 锁 ==============

let accountsLoadInFlight: Promise<void> | null = null
let extrasLoadInFlight: Promise<void> | null = null
let checkinLoadInFlight: Promise<void> | null = null

// ============== 后台异步:账号列表 ==============

/**
 * 拉取 /api/accounts,写 localStorage,更新 accounts.value
 * <p>
 * 不抛异常(失败时 console.warn 即可,store 状态保持为最近一次成功的快照)
 */
async function fetchAccountsFromServer(): Promise<void> {
  try {
    const res = await fetch('/api/accounts')
    if (!res.ok) throw new Error(`status=${res.status}`)
    const body = await res.json()
    const rawList: any[] = body.data || []
    setCachedAccounts(rawList.map((it) => ({
      id: it.id,
      uid: it.uid || '-',
      accountJson: it.accountJson ?? '',
      accessToken: it.accessToken || null,
      apiKey: it.apiKey || null,
      enabled: it.enabled !== false,
      updatedAt: it.updatedAt,
    })))

    reloadAccountsFromStorage()
  } catch (e) {
    console.warn('[accountData] 拉取账号列表失败:', e)
  }
}

/**
 * 确保账号列表已加载(同步返回缓存;缓存空时阻塞;后台总在拉取后更新 ref)
 * <p>
 * 多个组件并发调用安全(in-flight 锁)
 */
async function ensureAccountsLoaded(): Promise<void> {
  // 缓存有数据 → 立刻把 ref 同步一次(可能其它 tab 已更新),然后后台异步刷新
  reloadAccountsFromStorage()
  if (accounts.value.length > 0) {
    void fetchAccountsFromServer()
    return
  }
  // 缓存空 → 阻塞等待
  if (!accountsLoadInFlight) {
    accountsLoadInFlight = fetchAccountsFromServer().finally(() => {
      accountsLoadInFlight = null
    })
  }
  await accountsLoadInFlight
}

// ============== 后台异步:积分 / 用量 ==============

/**
 * 拉取单个账号的 credit / usage,写 localStorage
 * <p>
 * 后端路径用 accountId(id 主键);localStorage 缓存仍按 uid 索引
 */
async function fetchOneExtra(acct: CachedAccount): Promise<void> {
  const creditUrl = `/api/billing/${acct.id}/user-resource`
  const usageUrl = `/api/billing/${acct.id}/user-request-usage`
  let wroteSomething = false
  const tasks = [
    fetch(creditUrl, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
      .then((res) => (res.ok ? res.json() : null))
      .then((body) => {
        const snap = body?.extra?.credit
        if (snap && typeof snap === 'object') {
          mergeAccountExtra(acct.uid, 'credit', snap)
          wroteSomething = true
        }
      })
      .catch((e) => console.warn(`[accountData] 拉取 ${acct.uid} credit 失败:`, e)),
    fetch(usageUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(usageQueryBody(7)),
    })
      .then((res) => (res.ok ? res.json() : null))
      .then((body) => {
        const snap = body?.extra?.usage
        if (snap && typeof snap === 'object') {
          mergeAccountExtra(acct.uid, 'usage', snap)
          wroteSomething = true
        }
      })
      .catch((e) => console.warn(`[accountData] 拉取 ${acct.uid} usage 失败:`, e)),
  ]
  await Promise.allSettled(tasks)
  if (wroteSomething) {
    extrasVersion.value += 1
  }
}

/**
 * 构造"最近 N 天"的用量查询 body
 * <p>
 * 起始日 00:00:00，结束日 23:59:59 —— 与 BillingQueryService 的 UPSTREAM_DT_FMT 一致
 * 后端会补 pageNum/pageSize,无需前端传
 */
function usageQueryBody(days: number): Record<string, unknown> {
  const now = new Date()
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59)
  const start = new Date(end)
  start.setDate(start.getDate() - (days - 1))
  start.setHours(0, 0, 0, 0)
  const fmt = (d: Date) => {
    const p = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  }
  return {
    startTime: fmt(start),
    endTime: fmt(end),
  }
}

/**
 * 给所有账号并发拉取 credit / usage,失败时静默退回 localStorage
 * <p>
 * 确保账号列表已加载;in-flight 锁避免多个组件并发触发
 */
async function ensureExtrasLoaded(): Promise<void> {
  await ensureAccountsLoaded()
  if (extrasLoadInFlight) return extrasLoadInFlight
  const list = accounts.value
  if (list.length === 0) return
  extrasLoadInFlight = (async () => {
    await Promise.allSettled(list.map((a) => fetchOneExtra(a)))
  })().finally(() => {
    extrasLoadInFlight = null
  })
  await extrasLoadInFlight
}

/**
 * 主动刷新指定 uid 的 credit / usage(供签到成功后的"立即拉新积分"用)
 */
async function refreshOne(uid: string): Promise<void> {
  const acct = accounts.value.find((a) => a.uid === uid)
  if (!acct) return
  await fetchOneExtra(acct)
}

function updateLocalAccountEnabled(id: number, enabled: boolean): void {
 accounts.value = accounts.value.map((a) => (a.id === id ? { ...a, enabled } : a))
 setCachedAccounts(accounts.value)
}

/**
 * 乐观删除:从内存 ref + localStorage 立即移除指定 id 的账号
 * <p>
 * 调用方应先调后端 DELETE 接口,成功后再调本方法做 UI 立即反映;
 * 如果后端失败,需要重新 {@link ensureAccountsLoaded} 刷回真相。
 * <p>
 * 同时清理该 uid 的 extras / checkin 快照,避免后续 view 里出现"账号已删但积分还在"
 * 的孤儿数据
 *
 * @param id workbuddy_account.id 主键
 */
function removeLocalAccount(id: number): void {
  const target = accounts.value.find((a) => a.id === id)
  if (!target) {
    // ref 里没有,直接 reload 一次 localStorage 防漂移
    reloadAccountsFromStorage()
    return
  }
  // 1) 更新内存 ref(响应式触发 view 重算)
  accounts.value = accounts.value.filter((a) => a.id !== id)
  // 2) 重写 localStorage
  setCachedAccounts(accounts.value)
  // 3) 清掉该 uid 的 extras / checkin 快照,避免出现孤儿数据
  if (typeof window !== 'undefined' && target.uid) {
    try {
      const extrasRaw = localStorage.getItem('agentreproxy.account_extras')
      if (extrasRaw) {
        const map = JSON.parse(extrasRaw) as Record<string, unknown>
        if (target.uid in map) {
          delete map[target.uid]
          localStorage.setItem('agentreproxy.account_extras', JSON.stringify(map))
          extrasVersion.value += 1
        }
      }
      const checkinRaw = localStorage.getItem('agentreproxy.account_checkin')
      if (checkinRaw) {
        const map = JSON.parse(checkinRaw) as Record<string, unknown>
        if (target.uid in map) {
          delete map[target.uid]
          localStorage.setItem('agentreproxy.account_checkin', JSON.stringify(map))
          checkinVersion.value += 1
        }
      }
    } catch (e) {
      console.warn('[accountData] 清理被删账号的快照失败:', e)
    }
  }
}

// ============== 后台异步:签到状态 ==============

async function fetchCheckinStatus(): Promise<void> {
  if (accounts.value.length === 0) return
  // 只请求"还没拉过 / 缓存已跨日"的账号(避免每次都重拉,也避免把昨天的快照当成今天的状态)
  const needQuery = accounts.value
    .filter((a) => !isCheckinSnapshotFromToday(getCheckinSnapshot(a.uid)))
    .map((a) => a.id)
  if (needQuery.length === 0) return
  try {
    const res = await fetch('/api/checkin/status', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accountIds: needQuery }),
    })
    if (!res.ok) throw new Error(`status=${res.status}`)
    const body = await res.json()
    const items: any[] = body?.data || []
    const now = Date.now()
    let wroteSomething = false
    for (const it of items) {
      const acct = accounts.value.find((a) => a.id === it.accountId)
      if (!acct) continue
      const snap: CheckinSnapshot = it.checkedIn
        ? {
            checkedIn: true,
            checkinTime: it.checkinTime ?? null,
            checkinType: it.checkinType ?? 'daily',
            fetchedAt: now,
          }
        : { checkedIn: false, checkinTime: null, checkinType: null as any, fetchedAt: now }
      setCheckinSnapshot(acct.uid, snap)
      wroteSomething = true
    }
    if (wroteSomething) {
      checkinVersion.value += 1
    }
  } catch (e) {
    console.warn('[accountData] 拉取签到状态失败:', e)
  }
}

async function ensureCheckinLoaded(): Promise<void> {
  await ensureAccountsLoaded()
  if (checkinLoadInFlight) return checkinLoadInFlight
  checkinLoadInFlight = fetchCheckinStatus().finally(() => {
    checkinLoadInFlight = null
  })
  await checkinLoadInFlight
}

/**
 * 设置某个账号的签到状态(供签到成功后立即写入 localStorage)
 */
function setLocalCheckin(uid: string, snap: CheckinSnapshot): void {
  setCheckinSnapshot(uid, snap)
  checkinVersion.value += 1
}

// ============== 同步读数(组件直接用) ==============

function getCreditSnapshot(uid: string): CreditSnapshot | null {
  // 建立对 extrasVersion 的依赖:一旦 extrasVersion++，所有依赖这个 getter 的 computed/view 都会重算
  void extrasVersion.value
  return getAccountExtra(uid).credit ?? null
}

function getUsageSnapshot(uid: string): UsageSnapshot | null {
  void extrasVersion.value
  return getAccountExtra(uid).usage ?? null
}

function getCheckin(uid: string): CheckinSnapshot | null {
  void checkinVersion.value
  return getCheckinSnapshot(uid)
}

/**
 * 把账号列表 + 每个账号的 credit/usage/checkin 组合成一张"扁平视图"
 * <p>
 * 组件可以 watch 这个 computed(或在 onMounted 后读)直接拿到一整张表格行数据
 */
function buildView(): AccountDataView[] {
  return accounts.value.map((a) => ({
    id: a.id,
    uid: a.uid,
    nickname: a.nickname,
    accountJson: a.accountJson,
    accessToken: a.accessToken,
    apiKey: a.apiKey,
    enabled: a.enabled,
    updatedAt: a.updatedAt,
    credit: getCreditSnapshot(a.uid),
    usage: getUsageSnapshot(a.uid),
    checkin: getCheckin(a.uid),
  }))
}

// ============== 跨 tab 同步 ==============

function handleStorageEvent(e: StorageEvent): void {
  if (!e.key) return
  if (e.key === 'agentreproxy.accounts') {
    reloadAccountsFromStorage()
    return
  }
  if (e.key === 'agentreproxy.account_extras') {
    extrasVersion.value += 1
    return
  }
  if (e.key === 'agentreproxy.account_checkin') {
    checkinVersion.value += 1
  }
}

let listenerInstalled = false
function installCrossTabListener(): void {
  if (listenerInstalled || typeof window === 'undefined') return
  window.addEventListener('storage', handleStorageEvent)
  listenerInstalled = true
}

function uninstallCrossTabListener(): void {
  if (!listenerInstalled || typeof window === 'undefined') return
  window.removeEventListener('storage', handleStorageEvent)
  listenerInstalled = false
}

// ============== 导出:统一 API ==============

/**
 * 组件里这样用:
 * <pre>
 * const accountStore = useAccountData()
 * onMounted(async () => {
 *   await accountStore.ensureAccountsLoaded()
 * })
 * const rows = accountStore.view
 * </pre>
 * <p>
 * 注意:必须调用 {@code useAccountData()} 在 setup 里拿同一个 store,
 * 多个组件调它会拿到**同一个** ref,数据天然共享
 */
export function useAccountData() {
  // 第一次调用时挂上跨 tab 监听
  installCrossTabListener()

  return {
    // 响应式 state
    accounts: accounts as Readonly<Ref<CachedAccount[]>>,
    /** 计算属性:扁平视图(账号 + credit + usage + checkin) */
    get view(): AccountDataView[] {
      return buildView()
    },

    // 同步读数
    getCreditSnapshot,
    getUsageSnapshot,
    getCheckin,

    // 异步加载
    ensureAccountsLoaded,
    ensureExtrasLoaded,
    ensureCheckinLoaded,

    // 主动刷新
    refreshOne,
    updateLocalAccountEnabled,
    setLocalCheckin,

    // 乐观删除(配合后端 DELETE,立即清本地缓存 + ref,后端失败需重新 ensureAccountsLoaded 刷回)
    removeLocalAccount,

    // 跨 tab 监听管理(测试 / 卸载场景用)
    _installCrossTabListener: installCrossTabListener,
    _uninstallCrossTabListener: uninstallCrossTabListener,
  }
}
