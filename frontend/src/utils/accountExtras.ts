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
 * fetchedAt:    本地缓存的写入时间（毫秒）。用于判定快照是否仍属"今天"——跨天后视为过期,
 *               下次 ensureCheckinLoaded() 会重新拉一次后端。checkedIn=true 时 checkinTime
 *               本身就是写入时间,但仍记录 fetchedAt 是为了 checkedIn=false 的场景(后端
 *               返回"今天没签",前端没有别的参考点,只能用 fetch 时间)
 */
export interface CheckinSnapshot {
  checkedIn: boolean
  checkinTime: number | null
  checkinType: 'daily' | string
  fetchedAt: number
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
  enabled: boolean
  updatedAt: number
  /** 凭证过期时间(毫秒时间戳);null = 无过期信息(如 API Key 账号) */
  credentialExpiresAt: number | null
  /** refreshToken 过期时间(毫秒时间戳);null = 无此项 */
  refreshExpiresAt: number | null
}

/**
 * 从 JWT 的 payload 解出 `exp`（秒）并转成毫秒时间戳
 * <p>
 * 作为 `expiresAt` 字段缺失时的兜底 —— 老版本客户端写入的 accountJson
 * 可能只有 accessToken 而没有 expiresAt，但 JWT 本身自带 exp。
 * <p>
 * 只做 Base64 解码，不校验签名（我们只需要读过期时间做展示）。
 * 解不出或 payload 无 exp 时返回 null。
 */
function expiresAtFromJwt(token: string | null | undefined): number | null {
  if (!token || typeof token !== 'string') return null
  const parts = token.split('.')
  if (parts.length < 2) return null
  try {
    let b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    // 补齐 Base64 padding（JWT 规范要求去掉 padding，atob 要求有）
    b64 += '='.repeat((4 - (b64.length % 4)) % 4)
    const bin = atob(b64)
    // 用 TextDecoder 处理 UTF-8：直接 atob 会把中文昵称解成乱码，也让 JSON.parse 失败
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0))
    const payload = JSON.parse(new TextDecoder('utf-8').decode(bytes))
    const exp = payload?.exp
    if (typeof exp === 'number' && exp > 0) return exp * 1000
  } catch {
    /* 非标准 JWT（如 API Key、或已加密）→ 不提供过期信息 */
  }
  return null
}

/**
 * 解析 accountJson 中的凭证过期时间
 * <p>
 * 兼容结构与 {@link extractNicknameFromJson} 保持一致：
 * <ol>
 *   <li>嵌套：{@code obj.auth.expiresAt}</li>
 *   <li>数组：{@code obj.accounts[0].auth.expiresAt}（antigravity-tools 导出格式）</li>
 *   <li>扁平：{@code obj.expiresAt} / {@code obj.auth_expires_at}</li>
 * </ol>
 * 若都取不到，则回退解析 accessToken 的 JWT {@code exp}。
 * <p>
 * 返回 0 或负数视为无效（历史数据可能写入 0 表示"未记录"）。
 */
export function extractCredentialExpiry(
  accountJson: string | null | undefined
): { credentialExpiresAt: number | null; refreshExpiresAt: number | null; accessToken: string | null } {
  const empty = { credentialExpiresAt: null, refreshExpiresAt: null, accessToken: null }
  if (!accountJson) return empty
  let obj: any
  try {
    obj = JSON.parse(accountJson)
  } catch {
    return empty
  }

  // 三种结构统一取出 auth 节点
  const auth = obj?.auth ?? obj?.accounts?.[0]?.auth ?? null
  const accessToken: string | null =
    (typeof auth?.accessToken === 'string' && auth.accessToken) ||
    (typeof obj?.accessToken === 'string' && obj.accessToken) ||
    (typeof obj?.auth_token === 'string' && obj.auth_token) ||
    null

  /** 数字才认；0 / 负数 / 非数字一律当"未记录" */
  const pick = (...candidates: unknown[]): number | null => {
    for (const c of candidates) {
      if (typeof c === 'number' && c > 0) return c
    }
    return null
  }

  const credentialExpiresAt =
    pick(auth?.expiresAt, obj?.expiresAt, obj?.auth_expires_at) ??
    expiresAtFromJwt(accessToken)

  const refreshToken: string | null =
    (typeof auth?.refreshToken === 'string' && auth.refreshToken) || null
  const refreshExpiresAt =
    pick(auth?.refreshExpiresAt, obj?.refreshExpiresAt, obj?.auth_refresh_expires_at) ??
    expiresAtFromJwt(refreshToken)

  return { credentialExpiresAt, refreshExpiresAt, accessToken }
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
    return (parsed as Array<Partial<CachedAccount>>).map((item) => {
      const json = String(item.accountJson ?? '')
      const expiry = extractCredentialExpiry(json)
      return {
        id: Number(item.id),
        uid: String(item.uid ?? '-'),
        nickname: String(item.nickname ?? extractNicknameFromJson(json)),
        accountJson: json,
        accessToken: item.accessToken ?? expiry.accessToken ?? null,
        apiKey: item.apiKey ?? null,
        enabled: item.enabled !== false,
        updatedAt: Number(item.updatedAt ?? 0),
        // 缓存里可能存的是旧结构（无这两个字段）→ 现算一次，避免一次性显示"未知"
        credentialExpiresAt: item.credentialExpiresAt ?? expiry.credentialExpiresAt,
        refreshExpiresAt: item.refreshExpiresAt ?? expiry.refreshExpiresAt,
      }
    })
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
 * <p>
 * credentialExpiresAt / refreshExpiresAt 总是由 accountJson 现算(不信任调用方传入),
 * 因为它们是展示用的派生值,单一来源更不容易出错。
 */
export function setCachedAccounts(
  records: Array<Omit<CachedAccount, 'nickname' | 'credentialExpiresAt' | 'refreshExpiresAt'> & { nickname?: string }>
): void {
  // 防御性:过滤掉 uid 为 '-' 的兜底行(AccountManagement 列表里会有)
  const cleaned: CachedAccount[] = records
    .filter((r): r is Omit<CachedAccount, 'nickname' | 'credentialExpiresAt' | 'refreshExpiresAt'> & { nickname?: string } => Boolean(r && r.uid && r.uid !== '-'))
    .map((r) => {
      const expiry = extractCredentialExpiry(r.accountJson)
      return {
        id: r.id,
        uid: r.uid,
        accountJson: r.accountJson,
        // 后端返回的 accessToken 列为准;缺失时才从 accountJson 补(兼容 API Key 账号)
        accessToken: r.accessToken ?? expiry.accessToken,
        apiKey: r.apiKey,
        enabled: r.enabled !== false,
        updatedAt: r.updatedAt,
        nickname: r.nickname && r.nickname !== '未定义'
          ? r.nickname
          : extractNicknameFromJson(r.accountJson),
        credentialExpiresAt: expiry.credentialExpiresAt,
        refreshExpiresAt: expiry.refreshExpiresAt,
      }
    })
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

// ============== 跨日判定 ==============

/**
 * 把一个毫秒时间戳归零到"当天 00:00:00"（系统本地时区）
 * <p>
 * 用作日期比较：同一年的两个时间戳，归零后相等即代表同一天
 */
function startOfLocalDay(ts: number): number {
  const d = new Date(ts)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 0, 0, 0, 0).getTime()
}

/**
 * 判断一个签到快照是否仍属"今天"
 * <p>
 * 规则:
 *   - snap 为 null → false（从未拉过）
 *   - checkedIn=true → 以 checkinTime 的日期为准（与 checkin_log.checkin_time 对齐）
 *   - checkedIn=false → 以 fetchedAt 的日期为准（后端答"今天没签到",我们只记得"什么时候问的"）
 * <p>
 * 任意一个参考时间缺失都视为"过期"，强制下次重新拉取
 */
export function isCheckinSnapshotFromToday(snap: CheckinSnapshot | null, now: number = Date.now()): boolean {
  if (!snap) return false
  const todayStart = startOfLocalDay(now)
  if (snap.checkedIn) {
    if (typeof snap.checkinTime !== 'number') return false
    return startOfLocalDay(snap.checkinTime) === todayStart
  }
  if (typeof snap.fetchedAt !== 'number') return false
  return startOfLocalDay(snap.fetchedAt) === todayStart
}
