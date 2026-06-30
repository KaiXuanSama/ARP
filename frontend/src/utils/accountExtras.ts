/**
 * 账号 extra 信息 localStorage 缓存
 * <p>
 * key: agentreproxy.account_extras
 * 形态: { [uid: string]: { credit?: {...}, usage?: {...} } }
 */

export interface CreditPackage {
  packageName: string
  packageCode: string
  cycleCapacitySize: number
  cycleCapacityRemain: number
  cycleCapacityUsed: number
  cycleStartTime: string
  cycleEndTime: string
  status: number
}

export interface CreditSnapshot {
  fetchedAt: number
  totalCount: number
  totalDosage: number
  packages: CreditPackage[]
}

export interface UsageSnapshot {
  fetchedAt: number
  total: number  // 本期总消耗（积分）
}

/**
 * 每日签到快照
 * <p>
 * checkedIn:    当日是否已签到（权威源是后端 DB，前端只是即时显示）
 * checkinTime:  签到时间（毫秒），仅在 checkedIn=true 时有意义
 * checkinType:  签到类型（当前为 'daily'，未来可扩展）
 */
export interface CheckinSnapshot {
  checkedIn: boolean
  checkinTime: number | null
  checkinType: 'daily' | string
}

export interface AccountExtraSnapshot {
  credit?: CreditSnapshot
  usage?: UsageSnapshot
}

const STORAGE_KEY = 'agentreproxy.account_extras'
const CHECKIN_STORAGE_KEY = 'agentreproxy.account_checkin'
/** 账号列表缓存 key（由 AccountManagement 写入，Settings 等只读场景使用） */
const ACCOUNTS_STORAGE_KEY = 'agentreproxy.accounts'

// ============== 账号列表缓存（只读 API，AccountManagement 负责写入） ==============

/**
 * 缓存的账号条目（与 /api/accounts 返回的 WorkbuddyAccountRecord 同形，但额外提供 nickname 字段）
 * <p>
 * nickname 由 accountJson 解析而来(支持嵌套/扁平/数组三种结构),不存原 accountJson 以减小 localStorage 体积
 */
export interface CachedAccount {
  id: number
  uid: string
  nickname: string
  accountJson: string
  accessToken: string | null
  apiKey: string | null
  updatedAt: number
}

/**
 * 解析 accountJson 字符串,提取昵称
 * <p>
 * 兼容三种结构:
 *   1) 嵌套:  obj.account.nickname
 *   2) 数组:  obj.accounts[0].nickname  (antigravity-tools 导出格式)
 *   3) 扁平:  obj.nickname
 * <p>
 * 解析失败或字段为空时返回 '未定义'
 */
export function extractNicknameFromJson(accountJson: string | null | undefined): string {
  if (!accountJson) return '未定义'
  try {
    const obj = JSON.parse(accountJson)
    const candidates = [
      obj?.account?.nickname,
      obj?.accounts?.[0]?.nickname,
      obj?.nickname,
    ]
    for (const c of candidates) {
      if (typeof c === 'string' && c.trim() !== '') return c
    }
  } catch {
    /* fall through */
  }
  return '未定义'
}

function readCachedAccounts(): CachedAccount[] {
  try {
    const raw = localStorage.getItem(ACCOUNTS_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed as CachedAccount[]
  } catch {
    return []
  }
}

function writeCachedAccounts(list: CachedAccount[]): void {
  try {
    localStorage.setItem(ACCOUNTS_STORAGE_KEY, JSON.stringify(list))
  } catch (e) {
    console.warn('localStorage 写入账号列表失败:', e)
  }
}

/**
 * 读取缓存的账号列表(同步,无网络请求)。空数组表示还没被 AccountManagement 写过缓存
 */
export function getCachedAccounts(): CachedAccount[] {
  return readCachedAccounts()
}

/**
 * 由 AccountManagement.vue 在成功 fetch /api/accounts 后调用,刷新缓存
 * <p>
 * nickname 字段可选(AccountManagement 那边有自己的 extractNickname,通常会直接传),未提供时这里自动从 accountJson 解析填上
 * 同步提取 nickname 字段,后续 Settings.vue 等只读场景直接拿到现成的 nickname
 */
export function setCachedAccounts(
  records: Array<Omit<CachedAccount, 'nickname'> & { nickname?: string }>
): void {
  // 防御性:过滤掉 uid 为 '-' 的兜底行(AccountManagement 列表里会有)
  const cleaned: CachedAccount[] = records
    .filter((r): r is Omit<CachedAccount, 'nickname'> & { nickname?: string } => Boolean(r && r.uid && r.uid !== '-'))
    .map((r) => ({
      id: r.id,
      uid: r.uid,
      accountJson: r.accountJson,
      accessToken: r.accessToken,
      apiKey: r.apiKey,
      updatedAt: r.updatedAt,
      nickname: r.nickname && r.nickname !== '未定义'
        ? r.nickname
        : extractNicknameFromJson(r.accountJson),
    }))
  writeCachedAccounts(cleaned)
}

/**
 * 清除账号列表缓存(用于删除账号后让 Settings 重新拉取)
 */
export function clearCachedAccounts(): void {
  try {
    localStorage.removeItem(ACCOUNTS_STORAGE_KEY)
  } catch {
    /* ignore */
  }
}

type SnapshotMap = Record<string, AccountExtraSnapshot>

function readAll(): SnapshotMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? (parsed as SnapshotMap) : {}
  } catch {
    return {}
  }
}

function writeAll(map: SnapshotMap): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(map))
  } catch (e) {
    console.warn('localStorage 写入失败:', e)
  }
}

export function getAccountExtra(uid: string): AccountExtraSnapshot {
  if (!uid) return {}
  return readAll()[uid] ?? {}
}

export function setAccountExtra(uid: string, extra: AccountExtraSnapshot): void {
  if (!uid) return
  const all = readAll()
  all[uid] = { ...(all[uid] ?? {}), ...extra }
  writeAll(all)
}

export function mergeAccountExtra<K extends keyof AccountExtraSnapshot>(
  uid: string,
  key: K,
  value: AccountExtraSnapshot[K]
): void {
  if (!uid) return
  const all = readAll()
  const cur = all[uid] ?? {}
  all[uid] = { ...cur, [key]: value }
  writeAll(all)
}

export function clearAccountExtra(uid: string): void {
  if (!uid) return
  const all = readAll()
  delete all[uid]
  writeAll(all)
}

// ============== 签到快照（独立 key，便于清理与调试） ==============

type CheckinMap = Record<string, CheckinSnapshot>

function readAllCheckin(): CheckinMap {
  try {
    const raw = localStorage.getItem(CHECKIN_STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? (parsed as CheckinMap) : {}
  } catch {
    return {}
  }
}

function writeAllCheckin(map: CheckinMap): void {
  try {
    localStorage.setItem(CHECKIN_STORAGE_KEY, JSON.stringify(map))
  } catch (e) {
    console.warn('localStorage 写入签到快照失败:', e)
  }
}

export function getCheckinSnapshot(uid: string): CheckinSnapshot | null {
  if (!uid) return null
  const snap = readAllCheckin()[uid]
  return snap ?? null
}

export function setCheckinSnapshot(uid: string, snap: CheckinSnapshot): void {
  if (!uid) return
  const all = readAllCheckin()
  all[uid] = snap
  writeAllCheckin(all)
}

export function clearCheckinSnapshot(uid: string): void {
  if (!uid) return
  const all = readAllCheckin()
  delete all[uid]
  writeAllCheckin(all)
}
