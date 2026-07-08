<script setup lang="ts">
/**
 * 总览页面
 * <p>
 * 数据源:GET /api/overview —— 一次拉全部统计/趋势/健康度数据。
 * 图表库:echarts(按需引入 bar/pie/legend/tooltip/grid)。
 * 布局:Naive UI NCard + CSS Grid。
 */
import { onMounted, onUnmounted, ref, watch, nextTick } from 'vue'
import { NCard, NSpin, NButton, NTag, NProgress, useMessage } from 'naive-ui'
import * as echarts from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import type { BarSeriesOption } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import Icon from './Icon.vue'
import { authFetch } from '../utils/auth'

echarts.use([BarChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const message = useMessage()

// ============== 数据类型 ==============

interface OverviewData {
  stats: {
    accounts: { total: number; enabled: number; disabled: number }
    keys: { total: number; enabled: number; disabled: number; expired: number }
    todayCalls: number
    todayCheckin: { done: number; total: number }
    totalCredit: { remain: number; accountCount: number }
  }
  callTrend: Array<{ date: string; keyId: number | null; keyLabel: string; count: number }>
  callByKey: Array<{ keyId: number | null; keyLabel: string; count: number }>
  callByModel: Array<{ model: string; count: number }>
  creditHealth: Array<{
    accountId: number
    uid: string
    nickname: string
    enabled: boolean
    totalRemain: number
    totalCapacity: number
    nearestEndTime: string | null
    fetchedAt: number | null
  }>
}

// ============== 状态 ==============

const loading = ref(false)
const data = ref<OverviewData | null>(null)
const error = ref<string | null>(null)

// ============== Chart refs ==============

const trendChartRef = ref<HTMLDivElement | null>(null)
const keyPieRef = ref<HTMLDivElement | null>(null)
const modelPieRef = ref<HTMLDivElement | null>(null)

let trendChart: echarts.ECharts | null = null
let keyPieChart: echarts.ECharts | null = null
let modelPieChart: echarts.ECharts | null = null

// ============== 数据加载 ==============

async function fetchOverview() {
  loading.value = true
  error.value = null
  try {
    const res = await authFetch('/api/overview')
    if (!res.ok) {
      const body = await res.json().catch(() => ({}))
      throw new Error(body?.message || `请求失败: ${res.status}`)
    }
    data.value = await res.json()
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    error.value = msg
    message.error(`加载总览数据失败: ${msg}`)
  } finally {
    loading.value = false
  }
}

// ============== 图表渲染 ==============

function renderCharts() {
  if (!data.value) return
  nextTick(() => {
    renderTrendChart()
    renderKeyPie()
    renderModelPie()
  })
}

function renderTrendChart() {
  if (!trendChartRef.value || !data.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value)
  }

  const trend = data.value.callTrend
  // 收集所有日期和所有 keyLabel
  const dates = [...new Set(trend.map((t) => t.date))].sort()
  const keyLabels = [...new Set(trend.map((t) => t.keyLabel))]

  // 构建每个 key 的 series 数据
  const series: BarSeriesOption[] = keyLabels.map((label) => {
    const dataArr = dates.map((date) => {
      const hit = trend.find((t) => t.date === date && t.keyLabel === label)
      return hit ? hit.count : 0
    })
    return {
      name: label,
      type: 'bar' as const,
      stack: 'total',
      data: dataArr,
      barMaxWidth: 32,
    }
  })

  trendChart.setOption(
    {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
      },
      legend: {
        type: 'scroll',
        bottom: 0,
        textStyle: { fontSize: 11 },
      },
      grid: {
        left: 48,
        right: 16,
        top: 16,
        bottom: 40,
        containLabel: false,
      },
      xAxis: {
        type: 'category',
        data: dates.map((d) => d.slice(5)), // MM-DD
        axisLabel: { fontSize: 11 },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { fontSize: 11 },
      },
      series,
    },
    true,
  )
}

function renderKeyPie() {
  if (!keyPieRef.value || !data.value) return
  if (!keyPieChart) {
    keyPieChart = echarts.init(keyPieRef.value)
  }

  const pieData = data.value.callByKey.map((item) => ({
    name: item.keyLabel,
    value: item.count,
  }))

  keyPieChart.setOption(
    {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: {
        type: 'scroll',
        orient: 'horizontal',
        bottom: 0,
        textStyle: { fontSize: 11 },
      },
      series: [
        {
          type: 'pie',
          radius: ['36%', '62%'],
          center: ['50%', '42%'],
          avoidLabelOverlap: true,
          itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
          label: { show: false },
          emphasis: {
            label: { show: true, fontSize: 12, fontWeight: 'bold' },
          },
          data: pieData,
        },
      ],
    },
    true,
  )
}

function renderModelPie() {
  if (!modelPieRef.value || !data.value) return
  if (!modelPieChart) {
    modelPieChart = echarts.init(modelPieRef.value)
  }

  const pieData = data.value.callByModel.map((item) => ({
    name: item.model,
    value: item.count,
  }))

  modelPieChart.setOption(
    {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: {
        type: 'scroll',
        orient: 'horizontal',
        bottom: 0,
        textStyle: { fontSize: 11 },
      },
      series: [
        {
          type: 'pie',
          radius: ['36%', '62%'],
          center: ['50%', '42%'],
          avoidLabelOverlap: true,
          itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
          label: { show: false },
          emphasis: {
            label: { show: true, fontSize: 12, fontWeight: 'bold' },
          },
          data: pieData,
        },
      ],
    },
    true,
  )
}

// ============== 健康度辅助 ==============

function daysUntil(endTime: string | null): number | null {
  if (!endTime) return null
  try {
    const end = new Date(endTime).getTime()
    const now = Date.now()
    return Math.ceil((end - now) / (1000 * 60 * 60 * 24))
  } catch {
    return null
  }
}

function remainPercent(remain: number, total: number): number {
  if (total <= 0) return 0
  return Math.round((remain / total) * 100)
}

function healthStatus(remain: number, total: number, endTime: string | null): 'success' | 'warning' | 'error' {
  const pct = total > 0 ? remain / total : 0
  const days = daysUntil(endTime)
  if (pct <= 0.05 || (days !== null && days <= 3)) return 'error'
  if (pct <= 0.2 || (days !== null && days <= 7)) return 'warning'
  return 'success'
}

function formatAgo(ts: number | null): string {
  if (!ts) return '未知'
  const diff = Date.now() - ts
  if (diff < 60_000) return '刚刚'
  if (diff < 3600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86400_000) return `${Math.floor(diff / 3600_000)} 小时前`
  return `${Math.floor(diff / 86400_000)} 天前`
}

/**
 * 格式化积分显示
 * <p>
 * 数值 >= 10000 时显示 "x.x万"(< 1 位小数),更紧凑;否则显示完整数字(千分位)
 */
function formatCredit(value: number): string {
  if (!value || value <= 0) return '0'
  if (value >= 10000) {
    const v = value / 10000
    // 保留 1 位小数,尾零去掉
    return (Math.round(v * 10) / 10) + '万'
  }
  return Math.round(value).toLocaleString()
}

// ============== 响应式 resize ==============

function handleResize() {
  trendChart?.resize()
  keyPieChart?.resize()
  modelPieChart?.resize()
}

// ============== 生命周期 ==============

watch(data, () => {
  renderCharts()
})

onMounted(async () => {
  await fetchOverview()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  keyPieChart?.dispose()
  modelPieChart?.dispose()
  trendChart = null
  keyPieChart = null
  modelPieChart = null
})
</script>

<template>
  <div class="overview-page">
    <!-- 加载状态 -->
    <div v-if="loading && !data" class="loading-wrap">
      <n-spin size="large" />
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error && !data" class="error-wrap">
      <p class="error-text">{{ error }}</p>
      <n-button type="primary" @click="fetchOverview">重试</n-button>
    </div>

    <!-- 主内容 -->
    <template v-if="data">
      <!-- 第一层：核心统计卡片 -->
      <div class="stats-grid">
        <n-card class="stat-card" :bordered="true" size="small">
          <div class="stat-body">
            <div class="stat-icon stat-icon-blue">
              <Icon name="user" :size="20" />
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ data.stats.accounts.total }}</div>
              <div class="stat-label">上游账号</div>
            </div>
          </div>
          <div class="stat-footer">
            <span class="stat-tag stat-tag-green">启用 {{ data.stats.accounts.enabled }}</span>
            <span class="stat-tag stat-tag-gray">停用 {{ data.stats.accounts.disabled }}</span>
          </div>
        </n-card>

        <n-card class="stat-card" :bordered="true" size="small">
          <div class="stat-body">
            <div class="stat-icon stat-icon-purple">
              <Icon name="check-circle" :size="20" />
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ formatCredit(data.stats.totalCredit.remain) }}</div>
              <div class="stat-label">总积分余量</div>
            </div>
          </div>
          <div class="stat-footer">
            <span class="stat-tag stat-tag-muted">覆盖 {{ data.stats.totalCredit.accountCount }} 个账号</span>
          </div>
        </n-card>

        <n-card class="stat-card" :bordered="true" size="small">
          <div class="stat-body">
            <div class="stat-icon stat-icon-orange">
              <Icon name="refresh" :size="20" />
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ data.stats.todayCalls }}</div>
              <div class="stat-label">今日调用</div>
            </div>
          </div>
          <div class="stat-footer">
            <span class="stat-tag stat-tag-muted">chat 请求总量</span>
          </div>
        </n-card>

        <n-card class="stat-card" :bordered="true" size="small">
          <div class="stat-body">
            <div class="stat-icon stat-icon-green">
              <Icon name="check-circle" :size="20" />
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ data.stats.todayCheckin.done }}/{{ data.stats.todayCheckin.total }}</div>
              <div class="stat-label">今日签到</div>
            </div>
          </div>
          <div class="stat-footer">
            <span class="stat-tag stat-tag-muted">已签 / 总启用</span>
          </div>
        </n-card>
      </div>

      <!-- 第二层：调用分布 -->
      <div class="charts-row">
        <n-card class="chart-card chart-card-trend" :bordered="true" size="small" title="近 7 天调用分布">
          <div class="chart-trend-wrap">
            <div ref="trendChartRef" class="chart-container chart-container-tall" />
          </div>
        </n-card>

        <div class="pie-column">
          <n-card class="chart-card chart-card-pie" :bordered="true" size="small" title="各 Key 调用占比">
            <div ref="keyPieRef" class="chart-container chart-container-pie" />
          </n-card>
          <n-card class="chart-card chart-card-pie" :bordered="true" size="small" title="模型调用占比">
            <div ref="modelPieRef" class="chart-container chart-container-pie" />
          </n-card>
        </div>
      </div>

      <!-- 第三层：积分健康度 -->
      <n-card :bordered="true" size="small" title="积分健康度" class="health-card">
        <template v-if="data.creditHealth.length === 0">
          <div class="health-empty">暂无积分数据（请先在账号管理页刷新积分）</div>
        </template>
        <template v-else>
          <div class="health-table-wrap">
            <table class="health-table">
              <thead>
                <tr>
                  <th>账号</th>
                  <th>余量</th>
                  <th style="width: 200px">进度</th>
                  <th>最近到期</th>
                  <th>更新时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="account in data.creditHealth" :key="account.accountId">
                  <td class="health-account-cell">
                    <div class="health-nickname">{{ account.nickname }}</div>
                    <n-tag v-if="!account.enabled" size="tiny" type="warning">已停用</n-tag>
                  </td>
                  <td>
                    <span :class="['health-remain', `health-remain-${healthStatus(account.totalRemain, account.totalCapacity, account.nearestEndTime)}`]">
                      {{ account.totalRemain.toLocaleString() }}
                    </span>
                    <span class="health-total"> / {{ account.totalCapacity.toLocaleString() }}</span>
                  </td>
                  <td>
                    <n-progress
                      :percentage="remainPercent(account.totalRemain, account.totalCapacity)"
                      :status="healthStatus(account.totalRemain, account.totalCapacity, account.nearestEndTime)"
                      :show-indicator="false"
                      :height="8"
                      :border-radius="4"
                    />
                  </td>
                  <td>
                    <template v-if="account.nearestEndTime">
                      <span :class="{ 'health-warn': daysUntil(account.nearestEndTime) !== null && daysUntil(account.nearestEndTime)! <= 7 }">
                        {{ daysUntil(account.nearestEndTime) !== null ? `${daysUntil(account.nearestEndTime)} 天后` : account.nearestEndTime }}
                      </span>
                      <span v-if="daysUntil(account.nearestEndTime) !== null && daysUntil(account.nearestEndTime)! <= 3" class="health-alert">⚠️</span>
                    </template>
                    <span v-else class="health-na">—</span>
                  </td>
                  <td>
                    <span class="health-fetched">{{ formatAgo(account.fetchedAt) }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </n-card>

      <!-- 刷新按钮 -->
      <div class="refresh-bar">
        <n-button quaternary size="small" :loading="loading" @click="fetchOverview">
          <template #icon><Icon name="refresh" :size="14" /></template>
          刷新数据
        </n-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.overview-page {
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ============== 加载 / 错误 ============== */

.loading-wrap,
.error-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  gap: 16px;
}

.error-text {
  color: #d03050;
  font-size: 14px;
}

/* ============== 统计卡片 ============== */

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

@media (max-width: 900px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

.stat-card {
  border-radius: 8px;
}

.stat-body {
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-icon-blue  { background: #e8f4fd; color: #1890ff; }
.stat-icon-purple { background: #f3e8ff; color: #722ed1; }
.stat-icon-orange { background: #fff7e6; color: #fa8c16; }
.stat-icon-green  { background: #e6fffb; color: #13c2c2; }

.stat-info {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #1f2329;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: #8c8c8c;
  margin-top: 2px;
}

.stat-footer {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.stat-tag {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 4px;
  white-space: nowrap;
}

.stat-tag-green { background: #f6ffed; color: #52c41a; }
.stat-tag-gray  { background: #f5f5f5; color: #8c8c8c; }
.stat-tag-red   { background: #fff1f0; color: #f5222d; }
.stat-tag-muted { background: #f5f5f5; color: #8c8c8c; }

/* ============== 图表区 ============== */

/*
 * 响应式布局策略:
 * - 宽屏 (>=1300px): 2 列 —— trend 1.4fr | 右侧列(两饼图横向并排,各占 1fr)
 * - 中屏 (900~1300px): 2 列 —— trend 1.4fr | 右侧列(两饼图纵向堆叠)
 *   pie-column 内的 flex-direction 在中屏/窄屏时切换
 * - 窄屏 (<900px): 1 列垂直堆叠
 */
.charts-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  align-items: stretch;
}

@media (max-width: 899px) {
  .charts-row {
    grid-template-columns: 1fr;
  }
}

.chart-card {
  border-radius: 8px;
}

/* trend 卡片:让 echarts canvas 在卡片内容区上下居中,
 * 平衡与饼图卡片的高度差(饼图卡片更短) */
.chart-trend-wrap {
  width: 100%;
  height: 340px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chart-trend-wrap .chart-container {
  width: 100%;
  height: 100%;
}

/* 右侧饼图容器:宽屏横向并排,中屏及以下纵向堆叠 */
.pie-column {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  align-items: stretch;
  min-width: 0;
}

@media (max-width: 1299px) {
  .pie-column {
    grid-template-columns: minmax(0, 1fr);
  }
}

.chart-container-tall {
  width: 100%;
  height: 340px;
}

.chart-container-pie {
  width: 100%;
  height: 200px;
}

/* ============== 健康度 ============== */

.health-card {
  border-radius: 8px;
}

.health-empty {
  text-align: center;
  color: #8c8c8c;
  padding: 32px 0;
  font-size: 13px;
}

.health-table-wrap {
  overflow-x: auto;
}

.health-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.health-table th {
  text-align: left;
  font-weight: 600;
  color: #8c8c8c;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  white-space: nowrap;
}

.health-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #f5f5f5;
  vertical-align: middle;
}

.health-account-cell {
  vertical-align: top;
}

.health-nickname {
  font-weight: 600;
  color: #1f2329;
  margin-bottom: 4px;
}

.health-meta {
  display: flex;
  align-items: center;
  gap: 6px;
}

.health-fetched {
  font-size: 11px;
  color: #bfbfbf;
}

.health-remain {
  font-weight: 600;
}

.health-remain-success { color: #52c41a; }
.health-remain-warning { color: #faad14; }
.health-remain-error   { color: #f5222d; }

.health-total {
  color: #bfbfbf;
}

.health-warn {
  color: #faad14;
  font-weight: 500;
}

.health-alert {
  margin-left: 4px;
}

.health-na {
  color: #d9d9d9;
}

/* ============== 刷新栏 ============== */

.refresh-bar {
  display: flex;
  justify-content: flex-end;
  padding-bottom: 4px;
}
</style>
