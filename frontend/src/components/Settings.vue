<script setup lang="ts">
/**
 * 系统设置页面
 * <p>
 * 设计原则：全局读取 + 局部保存
 *  - 读取：GET /api/settings（一次拉全部设置项，前端按 key 分发到各卡片）
 *  - 保存：各设置项走各自的专有端点（带强类型校验），互不干扰
 * <p>
 * 当前设置项：
 *  - 账号管理：PUT /api/settings/admin/credential
 *  - 定时签到：PUT /api/settings/schedule/daily-checkin
 * <p>
 * 未来新增设置项时，在本页面追加新卡片 + 新 ref + 新 save 函数即可，
 * 每个卡片独立保存，不影响其他设置。
 */
import { computed, onMounted, ref } from 'vue'
import {
  NSwitch,
  NTimePicker,
  NButton,
  NSpace,
  NInput,
  NSelect,
  NCheckbox,
  useMessage,
} from 'naive-ui'
import { authFetch } from '../utils/auth'

const message = useMessage()

// ===== 账号管理设置块 =====

const ADMIN_KEY = 'admin.credential'

/** 当前管理员用户名（从后端读取，展示用） */
const currentUsername = ref('')
/** 表单字段 */
const newUsername = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const oldPassword = ref('')
/** 保存状态 */
const savingAdmin = ref(false)

/**
 * Save 按钮 handler — PUT /api/settings/admin/credential（专有端点）
 */
async function saveAdminCredential(): Promise<void> {
  if (savingAdmin.value) return

  // 前置校验
  if (!oldPassword.value) {
    message.warning('请输入旧密码')
    return
  }
  const hasNewUser = newUsername.value.trim().length > 0
  const hasNewPwd = newPassword.value.length > 0
  if (!hasNewUser && !hasNewPwd) {
    message.warning('新用户名和新密码至少需要填写一项')
    return
  }
  if (hasNewPwd && newPassword.value !== confirmPassword.value) {
    message.warning('两次输入的新密码不一致')
    return
  }

  savingAdmin.value = true
  try {
    const body: Record<string, string | null> = {
      newUsername: hasNewUser ? newUsername.value.trim() : null,
      newPassword: hasNewPwd ? newPassword.value : null,
      confirmPassword: hasNewPwd ? confirmPassword.value : null,
      oldPassword: oldPassword.value,
    }
    const res = await authFetch('/api/settings/admin/credential', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `请求失败: ${res.status}`)
    }
    const resBody = await res.json().catch(() => ({}))
    // 更新显示的当前用户名
    const updated = resBody?.data?.value
    if (updated && typeof updated === 'object' && updated.username) {
      currentUsername.value = updated.username
    }
    // 清空表单
    newUsername.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    oldPassword.value = ''
    message.success('凭证已更新')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`保存失败: ${msg}`)
  } finally {
    savingAdmin.value = false
  }
}

// ===== 定时签到设置块 =====

/** 是否启用 */
const scheduleEnabled = ref(false)
/** 触发时间(每日 HH:mm);NTimePicker 用毫秒值,展示时再格式化为 HH:mm */
const scheduleTimeMs = ref<number | null>(null)

/**
 * 校验错误态:启用开关打开但时间未选 → 飘红
 * <p>
 * 仅当 {@code enabled=true} 但 {@code time=null} 时为 true。
 * enabled=false 时不算错(用户主动关掉就不需要时间)
 */
const scheduleTimeError = computed<boolean>(
  () => scheduleEnabled.value && scheduleTimeMs.value == null,
)

/** Save 状态:false=未保存(默认值),true=已保存(用户点过 Save) */
const saving = ref(false)

/** 全局设置的 key —— 复用 app_settings 表 */
const SCHEDULE_KEY = 'schedule.dailyCheckin'

/** 后端 GET /api/settings 单条响应的 value 形态 */
interface SettingValue {
  enabled?: boolean
  time?: string | null
  [k: string]: unknown
}
/** 后端 GET /api/settings 列表响应的条目形态 */
interface SettingListItem {
  key: string
  value: SettingValue
  updatedAt?: number
}

/**
 * 从后端拉全部设置,本页面只关心 {@link SCHEDULE_KEY} 一条
 * <p>
 * <strong>为什么用 list 而不是 GET 单条</strong>:未来新增设置项(主题 / 默认分页大小 等)时,
 * 列表端点一次拉全,新增 UI 块直接消费本地缓存,无需为每个新项单独发请求。
 * 当前只有一条配置,逻辑反而更简单 —— find 一下就拿到。
 * <p>
 * 响应壳为 {@code {data: [...]}} —— 后端 {@code SettingsController.list()} 统一返回。
 */
async function loadFromServer(): Promise<void> {
  try {
    const res = await authFetch('/api/settings')
    if (!res.ok) {
      throw new Error(`status=${res.status}`)
    }
    const body = (await res.json().catch(() => ({}))) as { data?: SettingListItem[] }
    const items = body.data ?? []

    // 账号管理卡片：读取当前用户名
    const adminItem = items.find((it) => it.key === ADMIN_KEY)
    if (adminItem && adminItem.value && typeof adminItem.value === 'object') {
      currentUsername.value = (adminItem.value as Record<string, unknown>).username as string ?? ''
    }

    // 请求文本替换卡片
    const replaceItem = items.find((it) => it.key === TEXT_REPLACE_KEY)
    if (replaceItem && replaceItem.value && typeof replaceItem.value === 'object') {
      const rv = replaceItem.value as Record<string, unknown>
      replaceEnabled.value = rv.enabled === true
      const rules = rv.rules
      if (Array.isArray(rules)) {
        replaceRules.value = rules.map((r) => {
          const o = (r ?? {}) as Record<string, unknown>
          return {
            name: (o.name as string) ?? '',
            pattern: (o.pattern as string) ?? '',
            replacement: (o.replacement as string) ?? '',
            regex: o.regex === true,
            caseSensitive: o.caseSensitive === true,
            scope: (o.scope as string) ?? 'all_messages',
            enabled: o.enabled !== false,
          }
        })
      }
    }

    // 定时签到卡片
    const scheduleItem = items.find((it) => it.key === SCHEDULE_KEY)
    if (!scheduleItem) {
      return
    }
    const v = scheduleItem.value
    if (v && typeof v === 'object') {
      scheduleEnabled.value = v.enabled === true
      scheduleTimeMs.value = v.time ? hHmmStringToMs(v.time) : null
    }
  } catch (e) {
    console.warn('[Settings] 拉取初始设置失败:', e)
  }
}

/**
 * Save 按钮 handler — PUT /api/settings/schedule/daily-checkin（专有端点）
 * <p>
 * 读取仍走全局 GET /api/settings（一次拉全部），但保存走专有端点：
 * - 后端强类型 DTO + 业务校验（enabled=true 时 time 必填且格式正确）
 * - 路径更具体，语义更清晰
 * - 未来新增设置项（主题 / 默认分页大小等），各自走独立端点保存，互不干扰
 */
async function saveSettings(): Promise<void> {
  if (saving.value) return
  // 前置校验:启用但未选时间,拦截保存
  if (scheduleEnabled.value && scheduleTimeMs.value == null) {
    message.warning('请先选择定时签到时间,再保存')
    return
  }
  saving.value = true
  try {
    const time = scheduleTimeMs.value == null ? null : msToHHmm(scheduleTimeMs.value)
    const body = {
      enabled: scheduleEnabled.value,
      time,
    }
    const res = await authFetch('/api/settings/schedule/daily-checkin', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `请求失败: ${res.status}`)
    }
    // 读掉响应体即可(后端返回最新 row,这里不用关心)
    await res.json().catch(() => null)
    message.success(`设置已保存`)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`保存失败: ${msg}`)
  } finally {
    saving.value = false
  }
}

/**
 * NTimePicker 接受 number(ms) 或 string;内部统一用 number,展示时再格式化
 * <p>
 * NTimePicker 默认 value=null 时占位显示 "--:--"
 */
function msToHHmm(ms: number): string {
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 把后端回包的 "HH:mm" 字符串还原成 NTimePicker 用的 ms
 * <p>
 * NTimePicker 的 v-model 是 number(ms);后端存的是 "HH:mm" 字符串
 * 反向解析:今天的对应时刻(只要时分,日期无关)
 */
function hHmmStringToMs(hhmm: string): number {
  const parts = hhmm.split(':')
  const h = parseInt(parts[0] ?? '0', 10)
  const m = parseInt(parts[1] ?? '0', 10)
  const d = new Date()
  d.setHours(h, m, 0, 0)
  return d.getTime()
}


// ===== 请求文本替换设置块 =====

/** app_settings 中的 key */
const TEXT_REPLACE_KEY = 'request.textReplace'

/** 单条规则的前端形态 */
interface ReplaceRule {
  name: string
  pattern: string
  replacement: string
  regex: boolean
  caseSensitive: boolean
  scope: string
  enabled: boolean
}

/** 总开关 */
const replaceEnabled = ref(false)
/** 规则列表 */
const replaceRules = ref<ReplaceRule[]>([])
/** 保存状态 */
const savingReplace = ref(false)

/** 生效范围下拉项 */
const scopeOptions = [
  { label: '所有消息', value: 'all_messages' },
  { label: '仅 system 消息', value: 'system_only' },
  { label: '仅 user 消息', value: 'user_only' },
  { label: '仅工具描述', value: 'tool_descriptions' },
  { label: '消息 + 工具描述', value: 'messages_and_tools' },
]

/** 添加一条空规则 */
function addRule(): void {
  replaceRules.value.push({
    name: '',
    pattern: '',
    replacement: '',
    regex: false,
    caseSensitive: false,
    scope: 'all_messages',
    enabled: true,
  })
}

/** 删除指定规则 */
function removeRule(idx: number): void {
  replaceRules.value.splice(idx, 1)
}

/**
 * 保存规则 — PUT /api/settings/request/text-replace
 * <p>
 * 后端会校验正则合法性、长度上限与 scope 取值，不合法直接拒绝保存。
 */
async function saveTextReplace(): Promise<void> {
  if (savingReplace.value) return

  // 前置校验：匹配内容不能为空
  const emptyIdx = replaceRules.value.findIndex((r) => !r.pattern.trim())
  if (emptyIdx >= 0) {
    message.warning(`第 ${emptyIdx + 1} 条规则的「匹配内容」不能为空`)
    return
  }

  savingReplace.value = true
  try {
    const res = await authFetch('/api/settings/request/text-replace', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        enabled: replaceEnabled.value,
        rules: replaceRules.value,
      }),
    })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `请求失败: ${res.status}`)
    }
    await res.json().catch(() => null)
    message.success('替换规则已保存')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`保存失败: ${msg}`)
  } finally {
    savingReplace.value = false
  }
}

// ----- 预览（仅本机执行，不落库、不发上游）-----

const showPreview = ref(false)
const previewText = ref('')
const previewScope = ref('system')
const previewResult = ref<string | null>(null)
const previewCount = ref(0)
const previewActiveRules = ref(0)
const previewing = ref(false)

const previewScopeOptions = [
  { label: 'system 消息', value: 'system' },
  { label: 'user 消息', value: 'user' },
  { label: '工具描述', value: 'tool_desc' },
]

async function runPreview(): Promise<void> {
  if (previewing.value) return
  previewing.value = true
  try {
    const res = await authFetch('/api/settings/request/text-replace/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        rules: { enabled: replaceEnabled.value, rules: replaceRules.value },
        sampleText: previewText.value,
        scope: previewScope.value,
      }),
    })
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({}))
      throw new Error(errBody?.message || `请求失败: ${res.status}`)
    }
    const body = await res.json()
    const d = body?.data ?? {}
    previewResult.value = d.result ?? ''
    previewCount.value = d.replaceCount ?? 0
    previewActiveRules.value = d.activeRules ?? 0
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`预览失败: ${msg}`)
  } finally {
    previewing.value = false
  }
}

// ===== 模型列表卡片（2026-10 动态目录） =====

import { useModels } from '../composables/useModels'

const { models: catalogModels, meta: catalogMeta, ensureModelsLoaded } = useModels()

/** 刷新中（POST /api/models/refresh 在途） */
const refreshingModels = ref(false)
/** 最近一次刷新的错误信息（成功后清空） */
const refreshError = ref('')

/** 抓取时间的展示格式（本地时间字符串；无 meta 时为空） */
const fetchedAtText = computed(() => {
  const at = catalogMeta.value?.fetchedAt
  if (!at) return '尚未拉取'
  return new Date(at).toLocaleString()
})

/**
 * 更新模型列表 — POST /api/models/refresh
 * <p>
 * 后端随机洗牌遍历启用账号、首个成功即止；成功后前端重拉目录展示新快照。
 */
async function refreshModelCatalog(): Promise<void> {
  if (refreshingModels.value) return
  refreshingModels.value = true
  refreshError.value = ''
  try {
    const res = await authFetch('/api/models/refresh', { method: 'POST' })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) {
      throw new Error(body?.message || body?.detail || `请求失败: ${res.status}`)
    }
    message.success(
      `已更新：${body?.count ?? '?'} 个模型（来源账号 ${body?.sourceLabel ?? '?'}）`,
    )
    // 重拉目录 + meta，卡片展示新快照
    await ensureModelsLoaded()
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    refreshError.value = msg
    message.error(`更新失败: ${msg}`)
  } finally {
    refreshingModels.value = false
  }
}

onMounted(async () => {
  // 模型目录快照（供卡片展示条数/来源/时间；失败静默——卡片有刷新按钮兜底）
  void ensureModelsLoaded()
  // 进入页面时,从后端拉一次默认值 —— 404 视为"首次未保存"
  await loadFromServer()
})
</script>

<template>
  <div class="settings">
    <!-- ========== 账号管理卡片 ========== -->
    <div class="card">
      <div class="card-header">
        <h3 class="card-title">账号管理</h3>
        <p class="card-desc">
          修改管理面板的登录凭证。当前用户名：<strong>{{ currentUsername || '—' }}</strong>
        </p>
      </div>
      <div class="card-body">
        <div class="setting-row">
          <span class="setting-row-label setting-row-label-fixed">新用户名</span>
          <div class="setting-row-input">
            <n-input
              v-model:value="newUsername"
              placeholder="不填则不修改"
              clearable
            />
          </div>
        </div>
        <div class="setting-row">
          <span class="setting-row-label setting-row-label-fixed">新密码</span>
          <div class="setting-row-input">
            <n-input
              v-model:value="newPassword"
              type="password"
              show-password-on="click"
              placeholder="不填则不修改"
              clearable
            />
          </div>
        </div>
        <div class="setting-row">
          <span class="setting-row-label setting-row-label-fixed">确认新密码</span>
          <div class="setting-row-input">
            <n-input
              v-model:value="confirmPassword"
              type="password"
              show-password-on="click"
              placeholder="再次输入新密码"
              :disabled="!newPassword"
              clearable
            />
          </div>
        </div>
        <div class="setting-row">
          <span class="setting-row-label setting-row-label-fixed">验证旧密码</span>
          <div class="setting-row-input">
            <n-input
              v-model:value="oldPassword"
              type="password"
              show-password-on="click"
              placeholder="必填，验证当前密码"
              clearable
            />
          </div>
        </div>
      </div>
      <div class="card-footer">
        <n-space justify="end">
          <n-button
            type="primary"
            :loading="savingAdmin"
            @click="saveAdminCredential"
          >
            保存
          </n-button>
        </n-space>
      </div>
    </div>

    <!-- ========== 定时签到卡片 ========== -->
    <div class="card">
      <div class="card-header">
        <h3 class="card-title">定时签到</h3>
        <p class="card-desc">开启后服务会按设定的时间自动给所有账号签到。关闭则不会触发自动签到。</p>
      </div>
      <div class="card-body">
        <!--
          设置块(.setting-row):
            - 行:左控件 + 右提示(可选)
          后续如需新增"主题 / 默认分页大小"等,在 .setting-row 后追加即可;
          复制现有结构,无需改 card-body。
        -->
        <div class="setting-row">
          <div class="setting-row-main">
            <n-switch v-model:value="scheduleEnabled" />
            <span class="setting-row-label">启用定时签到</span>
          </div>
          <div class="setting-row-extra">
            <n-time-picker
              v-model:value="scheduleTimeMs"
              :disabled="!scheduleEnabled"
              :clearable="true"
              format="HH:mm"
              placeholder="选择时间"
              :input-readonly="true"
              :status="scheduleTimeError ? 'error' : undefined"
            />
          </div>
        </div>
      </div>
      <div class="card-footer">
        <n-space justify="end">
          <n-button
            type="primary"
            :loading="saving"
            @click="saveSettings"
          >
            保存
          </n-button>
        </n-space>
      </div>
    </div>
  
    <!-- ========== 请求文本替换卡片 ========== -->
    <div class="card">
      <div class="card-header">
        <h3 class="card-title">请求文本替换</h3>
        <p class="card-desc">
          在请求发往上游<strong>之前</strong>，按规则替换请求体中的文本。
          规则完全由你自行配置，服务不内置任何规则。
          常见用途：内部代号脱敏、术语统一、兼容性适配。
        </p>
      </div>
      <div class="card-body">
        <div class="setting-row">
          <span class="setting-row-label setting-row-label-fixed">启用替换</span>
          <div class="setting-row-input">
            <n-switch v-model:value="replaceEnabled" />
            <span class="setting-hint">关闭后所有规则暂停生效，但配置会保留</span>
          </div>
        </div>

        <div v-if="replaceRules.length === 0" class="empty-rules">
          还没有规则。点击下方「添加规则」开始配置。
        </div>

        <div v-for="(rule, idx) in replaceRules" :key="idx" class="rule-item">
          <div class="rule-item-head">
            <n-input
              v-model:value="rule.name"
              placeholder="规则名（便于识别）"
              size="small"
              style="max-width: 200px"
            />
            <n-space :size="8" align="center">
              <n-switch v-model:value="rule.enabled" size="small" />
              <n-button size="tiny" quaternary type="error" @click="removeRule(idx)">
                删除
              </n-button>
            </n-space>
          </div>

          <div class="rule-item-body">
            <div class="rule-field">
              <label>匹配内容</label>
              <n-input
                v-model:value="rule.pattern"
                placeholder="要查找的文本或正则"
                size="small"
              />
            </div>
            <div class="rule-field">
              <label>替换为</label>
              <n-input
                v-model:value="rule.replacement"
                placeholder="留空表示删除匹配内容"
                size="small"
              />
            </div>
            <div class="rule-field">
              <label>生效范围</label>
              <n-select
                v-model:value="rule.scope"
                :options="scopeOptions"
                size="small"
              />
            </div>
            <div class="rule-field rule-field-checks">
              <n-checkbox v-model:checked="rule.regex" size="small">正则匹配</n-checkbox>
              <n-checkbox v-model:checked="rule.caseSensitive" size="small">
                区分大小写
              </n-checkbox>
            </div>
          </div>
        </div>

        <div class="setting-row">
          <n-space :size="10">
            <n-button size="small" @click="addRule">添加规则</n-button>
            <n-button size="small" @click="showPreview = !showPreview">
              {{ showPreview ? '收起预览' : '效果预览' }}
            </n-button>
          </n-space>
        </div>

        <!-- 预览区：仅本机执行，不落库、不发上游 -->
        <div v-if="showPreview" class="preview-box">
          <div class="setting-row">
            <span class="setting-row-label setting-row-label-fixed">样例文本</span>
            <div class="setting-row-input">
              <n-input
                v-model:value="previewText"
                type="textarea"
                :rows="3"
                placeholder="粘贴一段文本，看看规则会怎么改"
              />
            </div>
          </div>
          <div class="setting-row">
            <span class="setting-row-label setting-row-label-fixed">模拟位置</span>
            <div class="setting-row-input">
              <n-select
                v-model:value="previewScope"
                :options="previewScopeOptions"
                size="small"
                style="max-width: 200px"
              />
              <n-button size="small" :loading="previewing" @click="runPreview">
                执行预览
              </n-button>
            </div>
          </div>
          <div v-if="previewResult !== null" class="preview-result">
            <div class="preview-result-meta">
              命中 {{ previewCount }} 处 · 生效规则 {{ previewActiveRules }} 条
            </div>
            <pre class="preview-result-text">{{ previewResult }}</pre>
          </div>
        </div>

        <div class="setting-row setting-row-actions">
          <n-button
            type="primary"
            :loading="savingReplace"
            @click="saveTextReplace"
          >
            保存规则
          </n-button>
        </div>
      </div>
    </div>

    <!-- ========== 模型列表卡片（2026-10 动态目录） ========== -->
    <div class="card">
      <div class="card-header">
        <h3 class="card-title">模型列表</h3>
        <p class="card-desc">
          模型目录来自上游真实接口（按账号拉取），服务启动时自动获取一次，
          之后在此手动更新。下游 Key 的模型白名单将与该目录严格取交集。
        </p>
      </div>
      <div class="card-body">
        <div class="setting-row">
          <span class="setting-row-label setting-row-label-fixed">当前目录</span>
          <div class="setting-row-input">
            <span v-if="catalogModels.length > 0">
              共 <strong>{{ catalogModels.length }}</strong> 个模型
              <span class="catalog-meta">
                （来源账号 {{ catalogMeta.sourceLabel || '—' }} · 抓取于 {{ fetchedAtText }}）
              </span>
            </span>
            <span v-else class="catalog-empty">
              目录为空 —— 无可用账号或尚未成功拉取，/v1/models 将拒绝服务，请点击下方按钮更新
            </span>
          </div>
        </div>
        <div v-if="refreshError" class="setting-row">
          <span class="setting-row-label setting-row-label-fixed">上次错误</span>
          <div class="setting-row-input catalog-error">{{ refreshError }}</div>
        </div>
      </div>
      <div class="card-footer">
        <n-space justify="end">
          <n-button
            type="primary"
            :loading="refreshingModels"
            @click="refreshModelCatalog"
          >
            更新模型列表
          </n-button>
        </n-space>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
}

.card {
  background: #ffffff;
  border: 1px solid #ececf0;
  border-radius: 8px;
}

.card-header {
  padding: 14px 20px 0;
}

.card-title {
  margin: 0 0 2px 0;
  font-size: 15px;
  font-weight: 600;
  color: #1f2329;
}

.card-desc {
  margin: 0;
  font-size: 12px;
  color: #8c8c8c;
  line-height: 1.5;
}

.card-body {
  padding: 8px 20px 4px;
  display: flex;
  flex-direction: column;
}

/*
 * 设置块样式 —— 每行一个设置项
 * - 左侧:控件 + 标签
 * - 右侧:辅助控件(如时间选择器)
 * - 紧凑布局,无分割线
 */
.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 0;
}

.setting-row-main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}

.setting-row-label {
  font-size: 14px;
  color: #1f2329;
  white-space: nowrap;
}

.setting-row-extra {
  flex-shrink: 0;
}

/*
 * 账号管理卡片 —— 标签固定宽度 + 输入框自适应撑满
 */
.setting-row-label-fixed {
  width: 90px;
  flex-shrink: 0;
  text-align: right;
}

.setting-row-input {
  flex: 1;
  min-width: 0;
}

/*
 * 卡片底部 —— 固定 Save 按钮区域
 * - 始终在卡片底部
 * - Save 按钮靠右(用 n-space justify="end" 实现)
 * - 无特殊背景/圆角/顶分割线,简洁融入卡片
 */
.card-footer {
  padding: 8px 20px 12px;
}

.setting-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #999;
}

.empty-rules {
  padding: 16px;
  text-align: center;
  color: #999;
  font-size: 13px;
  background: rgba(0, 0, 0, 0.02);
  border-radius: 6px;
  margin-bottom: 12px;
}

.rule-item {
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
  background: rgba(0, 0, 0, 0.01);
}

.rule-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.rule-item-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.rule-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rule-field label {
  font-size: 12px;
  color: #666;
}

.rule-field-checks {
  flex-direction: row;
  align-items: center;
  gap: 16px;
  padding-top: 18px;
}

.preview-box {
  border: 1px dashed rgba(0, 0, 0, 0.15);
  border-radius: 8px;
  padding: 12px;
  margin: 12px 0;
}

.preview-result {
  margin-top: 10px;
}

.preview-result-meta {
  font-size: 12px;
  color: #666;
  margin-bottom: 6px;
}

.preview-result-text {
  background: rgba(0, 0, 0, 0.03);
  border-radius: 6px;
  padding: 10px;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 200px;
  overflow: auto;
}

.setting-row-actions {
  margin-top: 12px;
}

/* ===== 模型列表卡片 ===== */

.catalog-meta {
  font-size: 12px;
  color: #888;
}

.catalog-empty {
  color: #d03050;
  font-size: 13px;
}

.catalog-error {
  color: #d03050;
  font-size: 13px;
  word-break: break-all;
}
</style>
