<script setup lang="ts">
import { computed, h, nextTick, onMounted, ref } from 'vue'
import {
 NButton,
 NCard,
 NDataTable,
 NDescriptions,
 NDescriptionsItem,
 NDropdown,
 NInput,
 NModal,
 NSpin,
 NSpace,
 NUpload,
 useMessage,
 type DataTableColumns,
 type UploadFileInfo,
} from 'naive-ui'
import Icon from './Icon.vue'
import type {
 CheckinSnapshot,
 CreditSnapshot,
 UsageSnapshot,
} from '../utils/accountExtras'
import { useAccountData, type AccountDataView } from '../composables/useAccountData'

interface WorkbuddyInfo {
 account?: {
 uid?: string
 nickname?: string
 uin?: string
 type?: string
 pluginEnabled?: boolean
 phoneNumber?: string
 }
 auth?: {
 accessToken?: string
 tokenType?: string
 scope?: string
 domain?: string
 expiresAt?: number
 refreshExpiresAt?: number
 }
}

interface ApiKeyOnlyPayload {
 nickname: string
 apiKey: string
}

interface AccountRow {
 id: number
 uid: string
 nickname: string
 primaryCredential: string
 apiKey: string | null
 accessToken: string | null
 updatedAt: number
 credit?: CreditSnapshot
 usage?: UsageSnapshot
 checkin?: CheckinSnapshot
 checkinProgress?: 'pending' | 'running' | 'done' | 'error'
}

interface CheckinStreamEvent {
 type: 'started' | 'result' | 'summary'
 accountId: number | null
 uid: string | null
 nickname: string | null
 position: number | null
 total: number | null
 status: string | null
 message: string | null
 checkinTime: number | null
 okCount: number | null
 alreadyCount: number | null
 errCount: number | null
 durationMs: number | null
}

const message = useMessage()

const showCredentialModal = ref(false)
const credentialRow = ref<AccountRow | null>(null)

const showChooser = ref(false)

const showDetail = ref(false)
const loadingDetail = ref(false)
const savingDetail = ref(false)
const detailInfo = ref<WorkbuddyInfo | null>(null)
const showAccessToken = ref(false)

const showApiKeyForm = ref(false)
const apiKeyForm = ref<ApiKeyOnlyPayload>({ nickname: '', apiKey: '' })
const savingApiKey = ref(false)

const searchKeyword = ref('')
const tableData = ref<AccountRow[]>([])
const checkingIn = ref(false)

// ===== 签到按钮右键菜单（强制签到） =====
const showCheckinCtxMenu = ref(false)
const checkinCtxMenuX = ref(0)
const checkinCtxMenuY = ref(0)
const checkinCtxMenuOptions = [
 { label: '强制签到（忽略已签状态）', key: 'force-checkin' },
]

function onCheckinButtonContextMenu(e: MouseEvent) {
 e.preventDefault()
 showCheckinCtxMenu.value = false
 nextTick(() => {
  checkinCtxMenuX.value = e.clientX
  checkinCtxMenuY.value = e.clientY
  showCheckinCtxMenu.value = true
 })
}

function handleCheckinCtxMenuSelect(key: string) {
 showCheckinCtxMenu.value = false
 if (key === 'force-checkin') {
  forceCheckin()
 }
}

async function forceCheckin() {
 if (checkingIn.value) return
 const allIds = tableData.value.map((row) => row.id)
 if (allIds.length === 0) {
  message.info('没有账号可签到')
  return
 }
 checkingIn.value = true
 try {
  resetCheckinProgress(allIds)
  const res = await fetch('/api/checkin/execute-stream', {
   method: 'POST',
   headers: { 'Content-Type': 'application/json' },
   body: JSON.stringify({ accountIds: allIds, force: true }),
  })
  if (!res.ok) {
   message.error(`签到请求失败: ${res.status}`)
   return
  }
  const freshRows: AccountRow[] = []
  const summary = await consumeCheckinStream(res, freshRows)

  tableData.value
   .filter((row) => allIds.includes(row.id) && row.checkinProgress === 'running')
   .forEach((row) => { row.checkinProgress = 'error' })

  const parts: string[] = []
  if (summary.okCount > 0) parts.push(`成功 ${summary.okCount}`)
  if (summary.alreadyCount > 0) parts.push(`已签 ${summary.alreadyCount}`)
  if (summary.errCount > 0) parts.push(`失败 ${summary.errCount}`)
  message.success(`强制签到完成: ${parts.join(' / ') || '无变更'}`)

  if (freshRows.length > 0) {
   await refreshCreditForRows(freshRows)
  }
  clearFinishedCheckinProgress()
 } catch (e) {
  const msg = e instanceof Error ? e.message : '未知错误'
  tableData.value
   .filter((row) => row.checkinProgress === 'running')
   .forEach((row) => { row.checkinProgress = 'error' })
  message.error(`签到异常: ${msg}`)
 } finally {
  checkingIn.value = false
 }
}

const accountStore = useAccountData()

function openCredentialDetail(row: AccountRow) {
 credentialRow.value = row
 showCredentialModal.value = true
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
const filteredTableData = computed<AccountRow[]>(() => {
 const kw = searchKeyword.value.trim().toLowerCase()
 if (!kw) return tableData.value
 return tableData.value.filter((row) => {
 const nickname = (row.nickname || '').toLowerCase()
 const uid = (row.uid || '').toLowerCase()
 return nickname.includes(kw) || uid.includes(kw)
 })
})

const checkinButtonLabel = computed(() => checkingIn.value ? '签到进行中…' : '一键每日签到')

const columns: DataTableColumns<AccountRow> = [
 { title: '编号', key: 'id', width:70 },
 {
 title: '昵称',
 key: 'nickname',
 width:160,
 render: (row) => row.nickname || '未定义',
 },
 {
 title: '凭证',
 key: 'credentials',
 width:220,
 render: (row: AccountRow) => h(NButton, {
 quaternary: true,
 size: 'tiny',
 style: { fontFamily: "'SFMono-Regular',Consolas,'Liberation Mono',monospace", fontSize: '12px' },
 onClick: () => openCredentialDetail(row),
 }, () => row.primaryCredential || '-'),
 },
 {
 title: '积分剩余',
 key: 'creditRemain',
 width:140,
 render: (row: AccountRow) => {
 if (!row.credit || !Array.isArray(row.credit.packages)) return '-'
 const remain = row.credit.packages.reduce((s, p) => s + (p.cycleCapacityRemain ||0),0)
 const size = row.credit.packages.reduce((s, p) => s + (p.cycleCapacitySize ||0),0)
 return `${remain.toFixed(1)} / ${size.toFixed(0)}`
 },
 },
 {
 title: '本期消耗',
 key: 'usage',
 width:110,
 render: (row: AccountRow) => {
 if (!row.usage) return '-'
 return `${row.usage.total.toFixed(2)}积分`
 },
 },
 {
 title: '当日签到状态',
 key: 'checkin',
 width:160,
 render: (row: AccountRow) => {
 if (row.checkinProgress === 'running') {
 return h('span', { style: { color: '#2080f0', fontWeight: '500' } }, '签到中…')
 }
 if (row.checkinProgress === 'error') {
 return h('span', { style: { color: '#d03050', fontWeight: '500' } }, '签到失败')
 }
 if (!row.checkin) {
 return h('span', { style: { color: '#999' } }, '未知')
 }
 return row.checkin.checkedIn
 ? h('span', { style: { color: '#18a058', fontWeight: '500' } }, '已签到')
 : h('span', { style: { color: '#f0a020', fontWeight: '500' } }, '未签到')
 },
 },
 {
 title: '更新时间',
 key: 'updatedAt',
 width:180,
 render: (row: AccountRow) => formatTime(row.updatedAt),
 },
]

function buildRows(): AccountRow[] {
 return accountStore.view.map((v: AccountDataView) => ({
 id: v.id,
 uid: v.uid,
 nickname: v.nickname,
 primaryCredential: pickPrimaryCredential(v.apiKey, v.accessToken),
 apiKey: v.accessToken ? null : v.apiKey,
 accessToken: v.accessToken,
 updatedAt: v.updatedAt,
 credit: v.credit ?? undefined,
 usage: v.usage ?? undefined,
 checkin: v.checkin ?? undefined,
 checkinProgress: undefined,
 }))
}

async function loadAccounts() {
 await accountStore.ensureAccountsLoaded()
 tableData.value = buildRows()
}

async function refreshExtras() {
 await accountStore.ensureExtrasLoaded()
 tableData.value = buildRows()
 message.success('积分、用量信息已刷新')
}

function pickPrimaryCredential(apiKey?: string | null, accessToken?: string | null): string {
 if (apiKey && apiKey.trim()) return `APIKey ${maskToken(apiKey)}`
 if (accessToken && accessToken.trim()) return `Access ${maskToken(accessToken)}`
 return '-'
}

function maskToken(token: string): string {
 if (!token) return '-'
 if (token.length <=12) return token
 return token.slice(0,6) + '…' + token.slice(-4)
}

function formatTime(ts: number | null | undefined): string {
 if (!ts) return '-'
 return new Date(ts).toLocaleString('zh-CN')
}

const allCheckedIn = computed(() => {
 if (tableData.value.length ===0) return false
 return tableData.value.every((row) => row.checkin?.checkedIn === true)
})

async function queryCheckinStatus() {
 await accountStore.ensureCheckinLoaded()
 tableData.value = buildRows()
}

async function oneClickCheckin() {
 if (checkingIn.value) return
 const targets = tableData.value
 .filter((row) => row.checkin?.checkedIn !== true)
 .map((row) => row.id)
 if (targets.length ===0) {
 message.info('所有账号今日已签到')
 return
 }

 checkingIn.value = true
 try {
 resetCheckinProgress(targets)
 const res = await fetch('/api/checkin/execute-stream', {
 method: 'POST',
 headers: { 'Content-Type': 'application/json' },
 body: JSON.stringify({ accountIds: targets }),
 })
 if (!res.ok) {
 message.error(`签到请求失败: ${res.status}`)
 return
 }

 const freshRows: AccountRow[] = []
 const summary = await consumeCheckinStream(res, freshRows)

 tableData.value
 .filter((row) => targets.includes(row.id) && row.checkinProgress === 'running')
 .forEach((row) => {
 row.checkinProgress = 'error'
 })

 const parts: string[] = []
 if (summary.okCount >0) parts.push(`成功 ${summary.okCount}`)
 if (summary.alreadyCount >0) parts.push(`已签 ${summary.alreadyCount}`)
 if (summary.errCount >0) parts.push(`失败 ${summary.errCount}`)
 message.success(`签到完成: ${parts.join(' / ') || '无变更'}`)

 if (freshRows.length >0) {
 await refreshCreditForRows(freshRows)
 }
 clearFinishedCheckinProgress()
 } catch (e) {
 const msg = e instanceof Error ? e.message : '未知错误'
 tableData.value
 .filter((row) => row.checkinProgress === 'running')
 .forEach((row) => {
 row.checkinProgress = 'error'
 })
 message.error(`签到异常: ${msg}`)
 } finally {
 checkingIn.value = false
 }
}

function resetCheckinProgress(targetIds: number[]): void {
 tableData.value.forEach((row) => {
 if (targetIds.includes(row.id)) {
 row.checkinProgress = 'pending'
 }
 })
}

function clearFinishedCheckinProgress(): void {
 tableData.value.forEach((row) => {
 if (row.checkinProgress === 'done') {
 row.checkinProgress = undefined
 }
 })
}

async function consumeCheckinStream(
 res: Response,
 freshRows: AccountRow[]
): Promise<{ okCount: number, alreadyCount: number, errCount: number }> {
 if (!res.body) {
 throw new Error('签到流响应为空')
 }

 const reader = res.body.getReader()
 const decoder = new TextDecoder()
 let buffer = ''
 let summary = { okCount:0, alreadyCount:0, errCount:0 }

 while (true) {
 const { done, value } = await reader.read()
 buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
 const lines = buffer.split('\n')
 buffer = lines.pop() ?? ''

 for (const rawLine of lines) {
 const line = rawLine.trim()
 if (!line) continue
 summary = handleCheckinStreamEvent(line, freshRows, summary)
 }

 if (done) {
 const tail = buffer.trim()
 if (tail) {
 summary = handleCheckinStreamEvent(tail, freshRows, summary)
 }
 break
 }
 }

 return summary
}

function handleCheckinStreamEvent(
 raw: string,
 freshRows: AccountRow[],
 summary: { okCount: number, alreadyCount: number, errCount: number }
): { okCount: number, alreadyCount: number, errCount: number } {
 const evt = JSON.parse(raw) as CheckinStreamEvent
 if (evt.type === 'started') {
 applyCheckinStarted(evt)
 return summary
 }
 if (evt.type === 'result') {
 applyCheckinResult(evt, freshRows)
 return summary
 }
 return {
 okCount: evt.okCount ??0,
 alreadyCount: evt.alreadyCount ??0,
 errCount: evt.errCount ??0,
 }
}

function applyCheckinStarted(evt: CheckinStreamEvent): void {
 const row = tableData.value.find((item) => item.id === evt.accountId)
 if (!row) return
 row.checkinProgress = 'running'
}

function applyCheckinResult(evt: CheckinStreamEvent, freshRows: AccountRow[]): void {
 const row = tableData.value.find((item) => item.id === evt.accountId)
 if (!row) return

 const isOk = evt.status === 'checked_in' || evt.status === 'already_checked_in'
 if (isOk) {
 const snap: CheckinSnapshot = {
 checkedIn: true,
 checkinTime: evt.checkinTime ?? Date.now(),
 checkinType: 'daily',
 fetchedAt: Date.now(),
 }
 row.checkin = snap
 row.checkinProgress = 'done'
 if (row.uid && row.uid !== '-') {
 accountStore.setLocalCheckin(row.uid, snap)
 }
 if (evt.status === 'checked_in') {
 freshRows.push(row)
 }
 return
 }

 row.checkinProgress = 'error'
 message.warning(`账号 ${row.nickname || evt.uid || '-'} 签到失败: ${evt.message || '未知错误'}`)
}

async function refreshCreditForRows(rows: AccountRow[]) {
 if (rows.length ===0) return
 await Promise.allSettled(rows.map((row) => accountStore.refreshOne(row.uid)))
 tableData.value = buildRows()
 filteredTableData.value
}

function openChooser() {
 showChooser.value = true
 detailInfo.value = null
 apiKeyForm.value = { nickname: '', apiKey: '' }
}

async function loadFromLocal() {
 showChooser.value = false
 showDetail.value = true
 loadingDetail.value = true
 await queryCheckinStatus()
 detailInfo.value = null
 showAccessToken.value = false
 try {
 const res = await fetch('/api/workbuddy-info')
 if (!res.ok) throw new Error(`请求失败: ${res.status}`)
 detailInfo.value = await res.json()
 } catch (e: unknown) {
 const msg = e instanceof Error ? e.message : '未知错误'
 message.error(`获取本机信息失败: ${msg}`)
 showDetail.value = false
 } finally {
 loadingDetail.value = false
 }
}

async function handleFileUpload({ file }: { file: UploadFileInfo }) {
 if (!file.file) return false
 showChooser.value = false
 showDetail.value = true
 loadingDetail.value = true
 detailInfo.value = null
 showAccessToken.value = false
 try {
 const form = new FormData()
 form.append('file', file.file)
 const res = await fetch('/api/import-info', { method: 'POST', body: form })
 if (!res.ok) {
 const errBody = await res.json().catch(() => ({}))
 throw new Error(errBody?.message || `请求失败: ${res.status}`)
 }
 detailInfo.value = await res.json()
 } catch (e: unknown) {
 const msg = e instanceof Error ? e.message : '未知错误'
 message.error(`文件导入失败: ${msg}`)
 showDetail.value = false
 } finally {
 loadingDetail.value = false
 }
 return false
}

function openApiKeyForm() {
 showChooser.value = false
 apiKeyForm.value = { nickname: '', apiKey: '' }
 showApiKeyForm.value = true
}

async function saveApiKeyOnly() {
 if (!apiKeyForm.value.apiKey.trim()) {
 message.warning('请输入 API Key')
 return
 }
 savingApiKey.value = true
 try {
 const payload = {
 account: { nickname: apiKeyForm.value.nickname.trim() || undefined },
 apiKey: apiKeyForm.value.apiKey.trim(),
 }
 const res = await fetch('/api/accounts', {
 method: 'POST',
 headers: { 'Content-Type': 'application/json' },
 body: JSON.stringify(payload),
 })
 const body = await res.json().catch(() => ({}))
 if (res.ok) {
 const status = body.status as string
 const text = status === 'created' ? '新建成功' : status === 'updated' ? '已覆盖旧记录' : '保存成功'
 message.success(text)
 showApiKeyForm.value = false
 await loadAccounts()
 } else if (res.status ===409) {
 message.warning('该 API Key已存在，未发生变化，无需重复保存')
 } else {
 const msg = body?.message || `请求失败: ${res.status}`
 message.error(`保存失败: ${msg}`)
 }
 } catch (e: unknown) {
 const msg = e instanceof Error ? e.message : '未知错误'
 message.error(`保存失败: ${msg}`)
 } finally {
 savingApiKey.value = false
 }
}

function cancelDetail() {
 showDetail.value = false
 detailInfo.value = null
}

async function saveDetail() {
 if (!detailInfo.value) return
 savingDetail.value = true
 try {
 const res = await fetch('/api/accounts', {
 method: 'POST',
 headers: { 'Content-Type': 'application/json' },
 body: JSON.stringify(detailInfo.value),
 })
 const body = await res.json().catch(() => ({}))
 if (res.ok) {
 const status = body.status as string
 const text = status === 'created' ? '新建成功' : status === 'updated' ? '已覆盖旧记录' : '保存成功'
 message.success(text)
 showDetail.value = false
 detailInfo.value = null
 await loadAccounts()
 } else if (res.status ===409) {
 message.warning('该账户信息已存在且未发生变化，无需重复保存')
 } else {
 const msg = body?.message || `请求失败: ${res.status}`
 message.error(`保存失败: ${msg}`)
 }
 } catch (e: unknown) {
 const msg = e instanceof Error ? e.message : '未知错误'
 message.error(`保存失败: ${msg}`)
 } finally {
 savingDetail.value = false
 }
}

onMounted(async () => {
 await loadAccounts()
 await refreshExtras()
 await queryCheckinStatus()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="toolbar-left">
        <n-input v-model:value="searchKeyword" placeholder="搜索昵称" clearable style="width:240px">
          <template #prefix>
            <Icon name="search" :size="14" />
          </template>
        </n-input>
      </div>
      <div class="toolbar-right">
        <n-button quaternary @click="refreshExtras">
          <template #icon>
            <Icon name="refresh" :size="14" />
          </template>
          刷新
        </n-button>
        <n-button
          type="warning"
          :disabled="allCheckedIn || checkingIn"
          :loading="checkingIn"
          @click="oneClickCheckin"
          @contextmenu="onCheckinButtonContextMenu"
        >
         {{ checkinButtonLabel }}
        </n-button>
        <n-dropdown
          trigger="manual"
          placement="bottom-start"
          :show="showCheckinCtxMenu"
          :options="checkinCtxMenuOptions"
          :x="checkinCtxMenuX"
          :y="checkinCtxMenuY"
          @select="handleCheckinCtxMenuSelect"
          @clickoutside="showCheckinCtxMenu = false"
        />
        <n-button type="primary" @click="openChooser">
          <template #icon>
            <Icon name="plus" :size="14" />
          </template>
          添加账户
        </n-button>
      </div>
    </div>

    <n-card :bordered="false" class="table-card">
       <n-data-table :columns="columns" :data="filteredTableData" :bordered="false" :single-line="false" size="small"
        :row-key="(row: AccountRow) => row.id" />
    </n-card>

    <!--凭证详情弹窗 -->
    <n-modal v-model:show="showCredentialModal" preset="card" title="凭证详情" style="width:600px;">
      <n-descriptions label-placement="left" bordered :column="1" size="small">
        <n-descriptions-item label="昵称">{{ credentialRow?.nickname || '未定义' }}</n-descriptions-item>
        <n-descriptions-item label="API Key">
          <code style="word-break: break-all; font-size:12px;">{{ credentialRow?.apiKey || '-' }}</code>
        </n-descriptions-item>
        <n-descriptions-item label="Access Token">
          <code style="word-break: break-all; font-size:12px;">{{ credentialRow?.accessToken || '-' }}</code>
        </n-descriptions-item>
      </n-descriptions>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showCredentialModal = false">关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <!--三入口选择 -->
    <n-modal v-model:show="showChooser" preset="card" title="添加账户" style="width:520px;">
      <div class="entry-list">
        <button class="entry-item" type="button" @click="loadFromLocal">
          <div class="entry-icon">
            <Icon name="logo" :size="20" />
          </div>
          <div class="entry-body">
            <div class="entry-title">本机获取</div>
            <div class="entry-desc">读取本机 CodeBuddy已登录的账户信息</div>
          </div>
        </button>
        <n-upload accept=".info,application/json" :show-file-list="false" @before-upload="handleFileUpload">
          <button class="entry-item" type="button">
            <div class="entry-icon">
              <Icon name="refresh" :size="20" />
            </div>
            <div class="entry-body">
              <div class="entry-title">文件导入</div>
              <div class="entry-desc">选择一个 workbuddy-desktop.info文件</div>
            </div>
          </button>
        </n-upload>
        <button class="entry-item" type="button" @click="openApiKeyForm">
          <div class="entry-icon">
            <Icon name="settings" :size="20" />
          </div>
          <div class="entry-body">
            <div class="entry-title">手动输入 API Key</div>
            <div class="entry-desc">仅填写昵称与 API Key创建账户</div>
          </div>
        </button>
      </div>
    </n-modal>

    <!--本机/文件详情 -->
    <n-modal v-model:show="showDetail" preset="card" title="账户详情" style="width:640px;">
      <n-spin :show="loadingDetail">
        <template v-if="detailInfo">
          <n-card title="账户信息" size="small" :bordered="false" class="info-card">
            <n-descriptions label-placement="left" bordered :column="2" size="small">
              <n-descriptions-item label="昵称">{{ detailInfo.account?.nickname || '未定义' }}</n-descriptions-item>
              <n-descriptions-item label="UID">{{ detailInfo.account?.uid || '-' }}</n-descriptions-item>
              <n-descriptions-item label="UIN">{{ detailInfo.account?.uin || '-' }}</n-descriptions-item>
              <n-descriptions-item label="类型">{{ detailInfo.account?.type || '-' }}</n-descriptions-item>
              <n-descriptions-item label="手机号">{{ detailInfo.account?.phoneNumber || '-' }}</n-descriptions-item>
              <n-descriptions-item label="插件启用">{{ detailInfo.account?.pluginEnabled ? '是' : '否'
                }}</n-descriptions-item>
            </n-descriptions>
          </n-card>
          <n-card title="凭证信息" size="small" :bordered="false" class="info-card">
            <n-descriptions label-placement="left" bordered :column="1" size="small">
              <n-descriptions-item label="凭证类型">{{ detailInfo.auth?.accessToken ? 'AccessToken (JWT)' : '-'
                }}</n-descriptions-item>
              <n-descriptions-item label="Access Token">
                <span class="token-cell">
                  <code>{{ showAccessToken ? detailInfo.auth?.accessToken : maskToken(detailInfo.auth?.accessToken || '') }}</code>
                  <button v-if="detailInfo.auth?.accessToken" class="eye-btn" type="button"
                    @click="showAccessToken = !showAccessToken">
                    <Icon :name="showAccessToken ? 'eye-off' : 'eye'" :size="14" />
                  </button>
                </span>
              </n-descriptions-item>
              <n-descriptions-item label="域名">{{ detailInfo.auth?.domain || '-' }}</n-descriptions-item>
              <n-descriptions-item label="作用域">{{ detailInfo.auth?.scope || '-' }}</n-descriptions-item>
              <n-descriptions-item label="Token过期">{{ formatTime(detailInfo.auth?.expiresAt) }}</n-descriptions-item>
            </n-descriptions>
          </n-card>
        </template>
      </n-spin>
      <template #footer>
        <n-space justify="end">
          <n-button @click="cancelDetail" :disabled="savingDetail">取消</n-button>
          <n-button type="primary" :loading="savingDetail" :disabled="!detailInfo || loadingDetail"
            @click="saveDetail">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!--手动输入 API Key -->
    <n-modal v-model:show="showApiKeyForm" preset="card" title="手动输入 API Key" style="width:480px;">
      <n-form-item label="昵称（可选）" label-placement="top" :show-feedback="false">
        <n-input v-model:value="apiKeyForm.nickname" placeholder="留空则不设置昵称" clearable />
      </n-form-item>
      <n-form-item label="API Key" label-placement="top" :show-feedback="false" required>
        <n-input v-model:value="apiKeyForm.apiKey" placeholder="请输入 API Key" type="password" show-password-on="click"
          clearable />
      </n-form-item>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showApiKeyForm = false" :disabled="savingApiKey">取消</n-button>
          <n-button type="primary" :loading="savingApiKey" @click="saveApiKeyOnly">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar-right {
  display: flex;
  gap: 8px;
}

.table-card {
  background: #fff;
  border-radius: 8px;
}

.entry-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.entry-list :deep(.n-upload) {
  display: block;
  width: 100%;
}

.entry-list :deep(.n-upload-trigger) {
  display: block;
  width: 100%;
}

.entry-item {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  text-align: left;
  background: #fafbfc;
  border: 1px solid #ececf0;
  border-radius: 8px;
  padding: 14px 16px;
  cursor: pointer;
  font-family: inherit;
  transition: background .12s ease, border-color .12s ease;
}

.entry-item:hover {
  background: #f2f3f5;
  border-color: #d9dadd;
}

.entry-icon {
  width: 36px;
  height: 36px;
  border-radius: 6px;
  background: #1f2329;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.entry-body {
  flex: 1;
  min-width: 0;
}

.entry-title {
  font-size: 14px;
  font-weight: 500;
  color: #1f2329;
  margin-bottom: 2px;
}

.entry-desc {
  font-size: 12px;
  color: #8c8c8c;
}

.info-card {
  background: #fafbfc;
  border-radius: 6px;
  margin-bottom: 12px;
}

.info-card:last-child {
  margin-bottom: 0;
}

.token-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  word-break: break-all;
}

.eye-btn {
  background: transparent;
  border: 1px solid #ececf0;
  border-radius: 4px;
  padding: 2px 4px;
  cursor: pointer;
  color: #4e5969;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.eye-btn:hover {
  background: #f2f3f5;
}
</style>
