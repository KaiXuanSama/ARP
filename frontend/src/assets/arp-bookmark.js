/*
 * ARP 凭证回传脚本（书签用）
 * ------------------------------------------------------------------
 * 由 ARP 面板「添加账户 → 扫码登录」弹窗内联加载，整体 encodeURIComponent
 * 后作为 javascript: 书签的 href。用户把它拖到书签栏，在 CodeBuddy 登录
 * 成功页点击即可把明文凭证自动回传到 ARP。
 *
 * 依赖登录链接带上的三个 URL 参数：
 *   state   登录流程关联键，用于向上游领取明文 token
 *   arp     ARP 服务地址（书签脚本据此回传）
 *   ticket  一次性回传凭证（5 分钟有效、用后即废）
 *
 * 领取端点依赖登录会话 Cookie（credentials: include）；
 * 回传端点靠 ticket 自鉴权，故跨域用 credentials: omit 提交。
 *
 * 失败兜底：任何一步失败都会把凭证 JSON 复制到剪贴板并打印到控制台，
 * 用户可改用 ARP 弹窗里的「手动方案」粘贴提交。
 *
 * 注意：本文件可能被内联进页面，故不要在代码里书写 HTML 的脚本闭合标签
 *       （形如「小于号 + 斜杠 + script + 大于号」），会被 HTML 解析器提前截断。
 * ------------------------------------------------------------------
 */
(async () => {
  'use strict'

  const ENTERPRISE_API = '/console/login/enterprise'
  const POLL_INTERVAL_MS = 3000
  const POLL_TIMEOUT_MS = 300000
  const TOAST_ID = '__arp_bookmark_toast'

  /* 右下角浮层提示：不用 alert，避免阻塞登录页 */
  function toast(msg, kind) {
    const COLORS = { info: '#1a7fbf', ok: '#18a058', err: '#d03050' }
    let el = document.getElementById(TOAST_ID)
    if (!el) {
      el = document.createElement('div')
      el.id = TOAST_ID
      el.style.cssText = 'position:fixed;z-index:2147483647;right:20px;bottom:20px;max-width:420px;padding:16px 20px;border-radius:10px;font:13px/1.7 -apple-system,BlinkMacSystemFont,sans-serif;color:#fff;box-shadow:0 8px 28px rgba(0,0,0,.28);white-space:pre-wrap;word-break:break-word;'
      document.body.appendChild(el)
    }
    el.style.background = COLORS[kind] || COLORS.info
    el.textContent = msg
    return el
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms))
  }

  /* 必须用 TextDecoder 解码 JWT，否则中文昵称变乱码（枫桦 → æ«æ¦） */
  function decodeJwt(token) {
    try {
      const seg = token.split('.')[1]
      if (!seg) return {}
      const b64 = seg.replace(/-/g, '+').replace(/_/g, '/')
      const pad = b64 + '='.repeat((4 - (b64.length % 4)) % 4)
      const bytes = Uint8Array.from(atob(pad), (c) => c.charCodeAt(0))
      return JSON.parse(new TextDecoder('utf-8').decode(bytes))
    } catch (e) {
      return {}
    }
  }

  /* 领取明文 token；HTTP 401 = 尚未完成登录，返回 null 让调用方继续轮询 */
  async function fetchToken(state) {
    const resp = await fetch(ENTERPRISE_API + '?state=' + encodeURIComponent(state), {
      method: 'POST',
      credentials: 'include'
    })
    if (!resp.ok) return null
    const body = await resp.json().catch(() => null)
    if (!body || body.code !== 0 || !body.data || !body.data.accessToken) return null
    return body.data
  }

  /* 参数可能落在 search（正常）或 hash（SPA 路由改造过 URL）里，两处都找 */
  function readParam(name) {
    const fromSearch = new URLSearchParams(location.search).get(name)
    if (fromSearch) return fromSearch
    const hashQuery = location.hash.indexOf('?')
    if (hashQuery >= 0) {
      const fromHash = new URLSearchParams(location.hash.slice(hashQuery + 1)).get(name)
      if (fromHash) return fromHash
    }
    return null
  }

  const state = readParam('state')
  const arp = (readParam('arp') || '').replace(/\/+$/, '')
  const ticket = readParam('ticket')

  if (!state) {
    toast('未找到 state 参数。\n\n请从 ARP 面板「添加账户 → 扫码登录」弹窗点「打开登录页」进入本页，再点本书签。', 'err')
    return
  }
  if (!arp || !ticket) {
    toast('未找到 arp / ticket 参数。\n\n本页链接不是由 ARP 生成的（或跳转过程中参数丢失）。\n请在 ARP 面板重新发起一次扫码登录。', 'err')
    return
  }

  toast('正在领取凭证…\n若尚未扫码，请先完成手机扫码登录（最多等待 5 分钟）。', 'info')

  const deadline = Date.now() + POLL_TIMEOUT_MS
  let data = null
  while (Date.now() < deadline) {
    try {
      data = await fetchToken(state)
      if (data) break
    } catch (e) {
      /* 网络抖动：忽略，下个周期重试 */
    }
    await sleep(POLL_INTERVAL_MS)
  }

  if (!data) {
    toast('领取超时：5 分钟内未获取到凭证。\n\n请确认：\n① 已完成手机扫码登录\n② 停留在登录成功后的页面\n③ 回到 ARP 重新发起扫码登录', 'err')
    return
  }

  const claims = decodeJwt(data.accessToken)
  const payload = {
    ticket: ticket,
    uid: claims.sub || null,
    nickname: claims.nickname || claims.preferred_username || null,
    accessToken: data.accessToken,
    refreshToken: data.refreshToken || null,
    expiresAt: Date.now() + (data.expiresIn || 0) * 1000,
    refreshExpiresAt: Date.now() + (data.refreshExpiresIn || 0) * 1000
  }

  let imported = false
  try {
    const resp = await fetch(arp + '/api/accounts/import-token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'omit',
      body: JSON.stringify(payload)
    })
    const body = await resp.json().catch(() => ({}))
    if (resp.ok) {
      imported = true
      toast('导入成功：' + (payload.nickname || payload.uid || '未知账号') + '\n\n可关闭本页，回到 ARP 面板查看（列表会自动刷新）。', 'ok')
    } else {
      toast('ARP 拒绝了回传：' + (body.message || ('HTTP ' + resp.status)) + '\n\n凭证 JSON 已复制到剪贴板，可在 ARP 弹窗的「手动方案」里粘贴提交。', 'err')
    }
  } catch (e) {
    /* 跨域 fetch 失败的常见原因：ARP 地址不可达，或登录页是 https 而 ARP 是 http
       （混合内容拦截；仅 localhost / 127.0.0.1 例外） */
    const hint = arp.indexOf('http://') === 0 && arp.indexOf('localhost') < 0 && arp.indexOf('127.0.0.1') < 0
      ? '\n\n⚠️ 本页是 HTTPS 而 ARP 是 HTTP，浏览器会拦截这类请求（混合内容）。\n' +
        '解决办法：把 ARP 部署到 HTTPS 域名，或改用下方「手动方案」粘贴提交。'
      : ''
    toast('无法连接 ARP：' + arp + '\n\n' + String(e).slice(0, 90) + hint + '\n\n凭证 JSON 已复制到剪贴板，可在 ARP 弹窗的「手动方案」里粘贴提交。', 'err')
  }

  /* 兜底：无论回传成功与否都输出 JSON，便于手动提交 */
  const json = JSON.stringify(payload, null, 2)
  try {
    await navigator.clipboard.writeText(json)
  } catch (e) {
    /* 剪贴板不可用：控制台仍可手动复制 */
  }
  console.log('[ARP] 凭证 JSON（回传' + (imported ? '成功' : '失败') + '，可手动粘贴到 ARP 管理面板）：')
  console.log(json)
})()
