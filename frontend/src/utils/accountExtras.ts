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
