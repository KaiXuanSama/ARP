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

export interface AccountExtraSnapshot {
  credit?: CreditSnapshot
  usage?: UsageSnapshot
}

const STORAGE_KEY = 'agentreproxy.account_extras'

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
