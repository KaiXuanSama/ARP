/*
 * 校验「凭证有效期解析」逻辑（M4）
 * 用法：node tools/check-credential-expiry.js
 *
 * 为什么单独写这个脚本而不是引入 vitest：
 * 项目前端尚无测试基础设施（无 vitest 依赖），为了一个纯函数引入整套测试框架不划算。
 * 本脚本用 node 直接跑，零依赖，可随时手动执行或在 CI 里加一行。
 *
 * 被测逻辑与 frontend/src/utils/accountExtras.ts 中的 extractCredentialExpiry 保持一致
 * —— 若那边改了实现，这个脚本要同步（故意保持简单，避免引入构建依赖）。
 */
const assert = require('assert')

/* ============ 被测逻辑（从 accountExtras.ts 抄录） ============ */

function expiresAtFromJwt(token) {
  if (!token || typeof token !== 'string') return null
  const parts = token.split('.')
  if (parts.length < 2) return null
  try {
    let b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    b64 += '='.repeat((4 - (b64.length % 4)) % 4)
    const bin = Buffer.from(b64, 'base64')
    // TextDecoder 语义：Buffer.toString('utf8') 等价
    const payload = JSON.parse(bin.toString('utf8'))
    const exp = payload?.exp
    if (typeof exp === 'number' && exp > 0) return exp * 1000
  } catch {
    /* 非标准 JWT */
  }
  return null
}

function extractCredentialExpiry(accountJson) {
  const empty = { credentialExpiresAt: null, refreshExpiresAt: null, accessToken: null }
  if (!accountJson) return empty
  let obj
  try {
    obj = JSON.parse(accountJson)
  } catch {
    return empty
  }
  const auth = obj?.auth ?? obj?.accounts?.[0]?.auth ?? null
  const accessToken =
    (typeof auth?.accessToken === 'string' && auth.accessToken) ||
    (typeof obj?.accessToken === 'string' && obj.accessToken) ||
    (typeof obj?.auth_token === 'string' && obj.auth_token) ||
    null

  const pick = (...candidates) => {
    for (const c of candidates) {
      if (typeof c === 'number' && c > 0) return c
    }
    return null
  }

  const credentialExpiresAt =
    pick(auth?.expiresAt, obj?.expiresAt, obj?.auth_expires_at) ??
    expiresAtFromJwt(accessToken)

  const refreshToken = (typeof auth?.refreshToken === 'string' && auth.refreshToken) || null
  const refreshExpiresAt =
    pick(auth?.refreshExpiresAt, obj?.refreshExpiresAt, obj?.auth_refresh_expires_at) ??
    expiresAtFromJwt(refreshToken)

  return { credentialExpiresAt, refreshExpiresAt, accessToken }
}

/* ============ 测试辅助 ============ */

/** 构造一个带 exp 的 JWT（不签名，只用于解析） */
function makeJwt(payload) {
  const b64 = (o) => Buffer.from(JSON.stringify(o), 'utf8').toString('base64url')
  return `${b64({ alg: 'HS256' })}.${b64(payload)}.fake-sig`
}

let passed = 0
let failed = 0

function check(name, fn) {
  try {
    fn()
    passed++
    console.log(`  [OK] ${name}`)
  } catch (e) {
    failed++
    console.error(`  [FAIL] ${name}\n         ${e.message}`)
  }
}

console.log('凭证有效期解析校验\n')

/* ---------- 1. 嵌套格式（CodeBuddy 官方） ---------- */
check('嵌套格式：取 auth.expiresAt / auth.refreshExpiresAt', () => {
  const json = JSON.stringify({
    account: { uid: 'u1', nickname: '测试' },
    auth: {
      accessToken: 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig',
      expiresIn: 4751998,
      expiresAt: 1795333866263,
      refreshExpiresAt: 1795765866263,
    },
  })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, 1795333866263)
  assert.strictEqual(r.refreshExpiresAt, 1795765866263)
})

/* ---------- 2. 扫码导入格式 ---------- */
check('扫码导入格式（source=scan-login）：同样可解析', () => {
  const json = JSON.stringify({
    account: { uid: 'u2', nickname: '枫桦', type: 'personal' },
    auth: { accessToken: 'a.b.c', tokenType: 'Bearer', expiresAt: 1795397412004, refreshExpiresAt: 1795829412004 },
    source: 'scan-login',
  })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, 1795397412004)
  assert.strictEqual(r.refreshExpiresAt, 1795829412004)
})

/* ---------- 3. 数组格式（antigravity-tools 导出） ---------- */
check('数组格式：取 accounts[0].auth.expiresAt', () => {
  const json = JSON.stringify({
    accounts: [{ uid: 'u3', auth: { accessToken: 'x.y.z', expiresAt: 1800000000000 } }],
  })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, 1800000000000)
})

/* ---------- 4. 扁平格式 ---------- */
check('扁平格式：取顶层 auth_expires_at', () => {
  const json = JSON.stringify({ uid: 'u4', auth_token: 'x.y.z', auth_expires_at: 1800000001000 })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, 1800000001000)
})

/* ---------- 5. JWT 兜底 ---------- */
check('无 expiresAt 字段时：回退解析 accessToken 的 JWT exp', () => {
  const jwt = makeJwt({ sub: 'u5', exp: 1800000002 })
  const json = JSON.stringify({ account: { uid: 'u5' }, auth: { accessToken: jwt } })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, 1800000002000)
})

/* ---------- 6. JWT 含中文：不应解析失败 ---------- */
check('JWT payload 含中文昵称：仍能解出 exp（TextDecoder 语义）', () => {
  const jwt = makeJwt({ sub: 'u6', nickname: '枫桦', exp: 1800000003 })
  const r = expiresAtFromJwt(jwt)
  assert.strictEqual(r, 1800000003000, '中文必须正确解码，否则 JSON.parse 会失败')
})

/* ---------- 7. 显式值优先于 JWT ---------- */
check('显式 expiresAt 优先于 JWT exp', () => {
  const jwt = makeJwt({ sub: 'u7', exp: 1111111111 })
  const json = JSON.stringify({ auth: { accessToken: jwt, expiresAt: 1795000000000 } })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, 1795000000000, '应以字段值为准，不应被 JWT 覆盖')
})

/* ---------- 8. 0 / 负数视为未记录 ---------- */
check('expiresAt=0 视为未记录，回退到 JWT', () => {
  const jwt = makeJwt({ sub: 'u8', exp: 1800000004 })
  const json = JSON.stringify({ auth: { accessToken: jwt, expiresAt: 0 } })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, 1800000004000, '0 是"未记录"的哨兵值，不应被当成有效时间')
})

/* ---------- 9. API Key 账号（无过期概念） ---------- */
check('API Key 账号：返回 null（不误报过期）', () => {
  const json = JSON.stringify({ account: { uid: 'u9' }, apiKey: 'sk-xxx' })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, null)
  assert.strictEqual(r.refreshExpiresAt, null)
})

/* ---------- 10. 脏数据不抛异常 ---------- */
check('非 JSON / null / undefined：返回空结果且不抛异常', () => {
  assert.strictEqual(extractCredentialExpiry('not-json').credentialExpiresAt, null)
  assert.strictEqual(extractCredentialExpiry(null).credentialExpiresAt, null)
  assert.strictEqual(extractCredentialExpiry(undefined).credentialExpiresAt, null)
  assert.strictEqual(extractCredentialExpiry('').credentialExpiresAt, null)
  assert.strictEqual(extractCredentialExpiry('{}').credentialExpiresAt, null)
})

/* ---------- 11. 畸形 JWT 不抛异常 ---------- */
check('畸形 JWT（段数不足 / 非法 Base64）：返回 null 而非抛异常', () => {
  assert.strictEqual(expiresAtFromJwt('onlyonepart'), null)
  assert.strictEqual(expiresAtFromJwt('a.!!!invalid!!!.c'), null)
  assert.strictEqual(expiresAtFromJwt(''), null)
  assert.strictEqual(expiresAtFromJwt(null), null)
})

/* ---------- 12. refreshToken 的 JWT 兜底 ---------- */
check('refreshToken 也可从 JWT 兜底解出过期时间', () => {
  const at = makeJwt({ sub: 'u12', exp: 1800000005 })
  const rt = makeJwt({ sub: 'u12', exp: 1800000600, typ: 'Offline' })
  const json = JSON.stringify({ auth: { accessToken: at, refreshToken: rt } })
  const r = extractCredentialExpiry(json)
  assert.strictEqual(r.credentialExpiresAt, 1800000005000)
  assert.strictEqual(r.refreshExpiresAt, 1800000600000, 'refreshToken 的 exp 应被单独解析')
})

console.log(`\n通过 ${passed} 项，失败 ${failed} 项`)
process.exit(failed > 0 ? 1 : 0)
