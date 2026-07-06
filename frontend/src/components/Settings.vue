<script setup lang="ts">
/**
 * 系统设置页面
 * <p>
 * 设计原则：全局读取 + 局部保存
 *  - 读取：GET /api/settings（一次拉全部设置项，前端按 key 分发到各卡片）
 *  - 保存：各设置项走各自的专有端点（带强类型校验），互不干扰
 * <p>
 * 当前设置项：
 *  - 定时签到：PUT /api/settings/schedule/daily-checkin
 * <p>
 * 未来新增设置项时，在本页面追加新卡片 + 新 ref + 新 save 函数即可，
 * 每个卡片独立保存，不影响其他设置。
 */
import { computed, onMounted, ref } from 'vue'
import { NSwitch, NTimePicker, NButton, NSpace, useMessage } from 'naive-ui'

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
const message = useMessage()

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
    const res = await fetch('/api/settings')
    if (!res.ok) {
      throw new Error(`status=${res.status}`)
    }
    const body = (await res.json().catch(() => ({}))) as { data?: SettingListItem[] }
    const items = body.data ?? []
    const item = items.find((it) => it.key === SCHEDULE_KEY)
    if (!item) {
      // 该 key 从未保存过 —— 合法情况,沿用 ref 初值
      return
    }
    const v = item.value
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
    const res = await fetch('/api/settings/schedule/daily-checkin', {
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

onMounted(async () => {
  // 进入页面时,从后端拉一次默认值 —— 404 视为"首次未保存"
  await loadFromServer()
})
</script>

<template>
  <div class="settings">
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
 * 卡片底部 —— 固定 Save 按钮区域
 * - 始终在卡片底部
 * - Save 按钮靠右(用 n-space justify="end" 实现)
 * - 无特殊背景/圆角/顶分割线,简洁融入卡片
 */
.card-footer {
  padding: 8px 20px 12px;
}
</style>
