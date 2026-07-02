<script setup lang="ts">
/**
 * 系统设置页面
 * <p>
 * 当前只承载"定时签到"一项全局设置。设计为多设置块可扩展:
 * 每个 setting-block 是独立的一行(标题 + 控件),行间有分割线;卡片底部固定 Save 按钮靠右。
 * <p>
 * <strong>本期仅前端 UI</strong>:后端定时签到 API 暂未实现,Save 按钮按下时仅弹一个
 * 提示 toast("设置已保存到前端(后端 API 待对接)")。等后端 PUT /api/settings/...
 * 端点落地后,改 saveSettings() 里的 fetch 即可,UI 形态不动。
 * <p>
 * 复用现有的 {@code app_settings} 表 + JSON 存储约定 —— 后续 key 形如 {@code schedule.dailyCheckin},
 * value JSON 形如 {@code {"enabled":true,"time":"08:00"}}。
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

/** 从后端 GET 初始化默认值 —— 进入页面时调一次 */
async function loadFromServer(): Promise<void> {
  try {
    const res = await fetch(`/api/settings/${encodeURIComponent(SCHEDULE_KEY)}`)
    if (res.status === 404) {
      // 后端从未保存过 —— 合法情况,沿用 ref 初值
      return
    }
    if (!res.ok) {
      throw new Error(`status=${res.status}`)
    }
    const body = (await res.json().catch(() => ({}))) as { data?: { value?: { enabled?: boolean; time?: string | null } } }
    const v = body.data?.value
    if (v && typeof v === 'object') {
      scheduleEnabled.value = v.enabled === true
      scheduleTimeMs.value = v.time ? hHmmStringToMs(v.time) : null
    }
  } catch (e) {
    console.warn('[Settings] 拉取初始设置失败:', e)
  }
}

/**
 * Save 按钮 handler —— PUT /api/settings/schedule.dailyCheckin
 * <p>
 * 后端通用端点:不限定 key,任意 JSON 入库。响应跟 GET 一致,回包带最新 updatedAt。
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
    const res = await fetch(`/api/settings/${encodeURIComponent(SCHEDULE_KEY)}`, {
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
        <h3 class="card-title">系统设置</h3>
        <p class="card-desc">全局设置项。当前仅开放"定时签到"。</p>
      </div>
      <div class="card-body">
        <!--
          设置块(.setting-row):
            - 行:左控件 + 右提示(可选)
            - 行间有 border-bottom 形成分割线(最后一行不加)
          后续如需新增"主题 / 默认分页大小"等,在 .setting-row 后追加即可;
          复制现有结构,无需改 card-body。
        -->
        <div class="setting-block-heading">
          <span class="setting-block-heading-text">设置定时签到功能</span>
        </div>
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
  padding: 16px 20px;
  border-bottom: 1px solid #ececf0;
}

.card-title {
  margin: 0 0 4px 0;
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
  padding: 20px;
  display: flex;
  flex-direction: column;
  /* gap:0 —— 行间分割线由 .setting-row 的 border-bottom 提供,避免双重间距 */
}

/*
 * 设置块样式 —— 每行一个设置项
 * - 左侧:控件 + 标签
 * - 右侧:辅助控件(如时间选择器)
 * - 行间分割线:border-bottom(最后一行不加,通过 :last-child 去掉)
 */
.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}

.setting-row:last-child {
  /* 最后一个设置块不要分割线 */
  border-bottom: none;
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

/*
 * 设置块小标题 —— 独立一行,位于该块设置项的上方,左对齐
 * - 比卡片大标题(15px)小一号,用 13px 黑色加粗
 * - 不画分割线(用户原话),与下方 .setting-row 用 12px 间距区分
 * - 命名沿用 "setting-block-heading" 区别于 "setting-row-label" —— 后者是"控件文字提示"
 */
.setting-block-heading {
  padding: 12px 0;
  /*
   * 不要 border-bottom —— 用户原话"不需要加横线",块标题与下面 setting-row
   * 用间距区分(12px 上下),不重复画线
   */
}

.setting-block-heading-text {
  font-size: 13px;
  font-weight: 600;
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
 * - dirty=false 时按钮禁用(避免无意义保存)
 */
.card-footer {
  padding: 12px 20px;
  border-top: 1px solid #ececf0;
  background: #fafbfc;
  border-bottom-left-radius: 8px;
  border-bottom-right-radius: 8px;
}
</style>
