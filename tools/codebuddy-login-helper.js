/**
 * CodeBuddy 登录辅助脚本（ARP 账号获取用）
 * ==========================================
 *
 * 用途：在浏览器里完成一次扫码登录，领取明文的 accessToken / refreshToken，
 *       然后粘贴到 ARP 管理面板完成账号添加。
 *
 * 背景（2026-09 实测确认）：
 *   - 新版 `workbuddy-desktop.info` 里的凭证字段被字段级加密（`$wbEncrypted` 信封），
 *     无法直接读取，故需要本脚本走网络接口获取明文。
 *   - 明文接口：`POST /console/login/enterprise?state=<state>`
 *     → 返回 `{code:0, data:{accessToken, refreshToken, expiresIn, refreshExpiresIn}}`
 *   - 该接口需要 **登录会话 Cookie**（401 表示未登录），因此必须在
 *     已完成扫码的浏览器上下文里执行。
 *   - `state` 是本次登录流程的关联键，由发起方（本脚本）生成，
 *     服务端在登录完成后把 token 与 state 关联，凭 state 即可领取。
 *
 * 使用方法（两步）：
 *   1. 在任意 codebuddy.cn 页面上按 F12 打开控制台，粘贴本脚本回车
 *      → 脚本自动生成 state 并跳转到登录页（会显示二维码）
 *   2. 用手机扫码完成登录
 *   3. 等待页面显示「登录成功」后，**再次粘贴本脚本**回车
 *      → 脚本自动轮询领取 token 并打印结果（同时尝试复制到剪贴板）
 *
 * 注意：本流程需要「在浏览器中执行登录」这一步，纯服务端无法完成
 *      （token 兑换依赖浏览器会话 Cookie + 服务端持有的 PKCE verifier）。
 */
(async () => {
  'use strict';

  // ============ 配置 ============
  const LOGIN_PAGE = 'https://www.codebuddy.cn/login/';
  const LOGIN_VERSION = '5.1.7';        // 客户端版本号，随上游更新可调整
  const ENTERPRISE_API = '/console/login/enterprise';
  const POLL_INTERVAL_MS = 3000;        // 轮询间隔
  const POLL_TIMEOUT_MS = 5 * 60 * 1000; // 轮询上限（5 分钟）

  // ============ 工具函数 ============

  /** 从当前 URL 提取 state（登录页会保留该参数） */
  function extractState() {
    return new URLSearchParams(location.search).get('state');
  }

  /**
   * 解码 JWT payload
   * <p>
   * 必须用 TextDecoder 处理 UTF-8 —— 直接用 atob 解出的字符串会把
   * 中文昵称变成乱码（实测 `枫桦` 会显示成 `æ«æ¦`）。
   */
  function decodeJwtPayload(token) {
    try {
      const part = token.split('.')[1];
      if (!part) return null;
      const b64 = part.replace(/-/g, '+').replace(/_/g, '/');
      const pad = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
      const bin = atob(pad);
      const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
      return JSON.parse(new TextDecoder('utf-8').decode(bytes));
    } catch (e) {
      return null;
    }
  }

  /** 尝试用 state 领取 token */
  async function tryFetch(state) {
    const resp = await fetch(
      `${ENTERPRISE_API}?state=${encodeURIComponent(state)}`,
      { method: 'POST', credentials: 'include' },
    );
    if (!resp.ok) {
      return { ok: false, status: resp.status };
    }
    let body;
    try {
      body = await resp.json();
    } catch (e) {
      return { ok: false, status: resp.status, error: '响应不是 JSON' };
    }
    if (body.code !== 0 || !body.data || !body.data.accessToken) {
      return { ok: false, status: resp.status, body };
    }
    return { ok: true, data: body.data };
  }

  /** 输出结果（打印 + 尝试复制到剪贴板） */
  async function emit(data) {
    const at = decodeJwtPayload(data.accessToken) || {};
    const rt = decodeJwtPayload(data.refreshToken || '') || {};
    const now = Date.now();

    const result = {
      uid: at.sub || null,
      nickname: at.nickname || at.preferred_username || null,
      accessToken: data.accessToken,
      refreshToken: data.refreshToken || null,
      expiresAt: now + (data.expiresIn || 0) * 1000,
      refreshExpiresAt: now + (data.refreshExpiresIn || 0) * 1000,
    };

    const json = JSON.stringify(result, null, 2);
    const days = (s) => Math.round(s / 86400);

    console.log('');
    console.log('═'.repeat(72));
    console.log('✅ 领取成功 —— 复制下面的 JSON，粘贴到 ARP 管理面板');
    console.log('═'.repeat(72));
    console.log(json);
    console.log('═'.repeat(72));
    console.log('账号信息：');
    console.log('  uid        :', result.uid);
    console.log('  nickname   :', result.nickname);
    console.log('  token 来源 :', at.iss || '(未知)');
    console.log('  accessToken 有效期 :', days(data.expiresIn || 0), '天');
    console.log('  refreshToken 有效期:', days(data.refreshExpiresIn || 0), '天',
      rt.typ ? `（typ=${rt.typ}）` : '');
    console.log('═'.repeat(72));

    // 尝试写入剪贴板（失败不影响主流程）
    try {
      await navigator.clipboard.writeText(json);
      console.log('📋 JSON 已复制到剪贴板');
    } catch (e) {
      console.log('（自动复制失败，请手动选中上面的 JSON 复制）');
    }

    return result;
  }

  // ============ 主流程 ============

  const state = extractState();

  // ---------- 第一步：无 state → 生成并跳转到登录页 ----------
  if (!state) {
    const newState = (crypto.randomUUID
      ? crypto.randomUUID()
      : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
          const r = (Math.random() * 16) | 0;
          const v = c === 'x' ? r : (r & 0x3) | 0x8;
          return v.toString(16);
        }));

    const url = `${LOGIN_PAGE}?platform=workbuddy&state=${newState}&version=${LOGIN_VERSION}`;

    console.log('');
    console.log('═'.repeat(72));
    console.log('📱 登录辅助 · 第 1 步：生成登录链接');
    console.log('═'.repeat(72));
    console.log('state :', newState);
    console.log('链接  :', url);
    console.log('');
    console.log('即将跳转到登录页，请用手机扫码完成登录。');
    console.log('登录成功后，回到本页控制台，**再次粘贴本脚本**领取 token。');
    console.log('═'.repeat(72));

    location.href = url;
    return;
  }

  // ---------- 第二步：有 state → 轮询领取 ----------
  console.log('');
  console.log('═'.repeat(72));
  console.log('📱 登录辅助 · 第 2 步：领取凭证');
  console.log('═'.repeat(72));
  console.log('state :', state);
  console.log('若尚未扫码，脚本会自动等待（最多 5 分钟）。');
  console.log('═'.repeat(72));

  const deadline = Date.now() + POLL_TIMEOUT_MS;
  let attempt = 0;
  let lastStatus = null;

  while (Date.now() < deadline) {
    attempt++;
    let r;
    try {
      r = await tryFetch(state);
    } catch (e) {
      console.log(`  [${attempt}] 请求异常：${String(e).slice(0, 80)}`);
      await new Promise((s) => setTimeout(s, POLL_INTERVAL_MS));
      continue;
    }

    if (r.ok) {
      await emit(r.data);
      return;
    }

    lastStatus = r.status;
    if (r.status === 401) {
      // 401 = 尚未登录（会话无 Cookie 或登录未完成），继续等待
      console.log(`  [${attempt}] 等待扫码完成…（HTTP 401）`);
    } else {
      console.log(`  [${attempt}] 未领取到，HTTP ${r.status}`, r.body || '');
      // 非 401 通常是 state 不匹配，继续等意义不大
      if (r.status !== 401 && r.status !== 404) break;
    }

    await new Promise((s) => setTimeout(s, POLL_INTERVAL_MS));
  }

  console.log('');
  console.log('⚠️ 轮询结束，未能领取到凭证。');
  console.log('  最后状态码：', lastStatus);
  console.log('  可能原因：');
  console.log('   · 未完成扫码登录（401）');
  console.log('   · state 与登录时使用的不是同一个（请确认跳转链接未被改动）');
  console.log('   · 登录流程已超时，请从第 1 步重新开始');
})();
