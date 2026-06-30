<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import {
  NButton, NModal, NCard, NDescriptions, NDescriptionsItem,
  NSpin, NSpace, NDataTable, NInput, NUpload, useMessage,
  type DataTableColumns, type UploadFileInfo
} from 'naive-ui'
import Icon from './Icon.vue'
import {
  getAccountExtra,
  mergeAccountExtra,
  type CreditSnapshot,
  type UsageSnapshot,
} from '../utils/accountExtras'

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
}

const message = useMessage()

// =====凭证详情弹窗 =====
const showCredentialModal = ref(false)
const credentialRow = ref<AccountRow | null>(null)

function openCredentialDetail(row: AccountRow) {
  credentialRow.value = row
  showCredentialModal.value = true
}

// =====主弹窗：三入口选择 =====
const showChooser = ref(false)

// =====本机 /文件导入详情弹窗 =====
const showDetail = ref(false)
const loadingDetail = ref(false)
const savingDetail = ref(false)
const detailInfo = ref<WorkbuddyInfo | null>(null)
const showAccessToken = ref(false)

// =====手动输入 API Key弹窗 =====
const showApiKeyForm = ref(false)
const apiKeyForm = ref<ApiKeyOnlyPayload>({ nickname: '', apiKey: '' })
const savingApiKey = ref(false)

// =====表格 =====
const searchKeyword = ref('')
const tableData = ref<AccountRow[]>([])

const columns: DataTableColumns<AccountRow> = [
  { title: '编号', key: 'id', width: 70 },
  {
    title: '昵称',
    key: 'nickname',
    width: 160,
    render: (row) => row.nickname || '未定义',
  },
  {
    title: '凭证',
    key: 'credentials',
    width: 220,
    render: (row: AccountRow) => {
      return h(NButton, {
        quaternary: true,
        size: 'tiny',
        style: { fontFamily: "'SFMono-Regular',Consolas,'Liberation Mono',monospace", fontSize: '12px' },
        onClick: () => openCredentialDetail(row),
      }, () => row.primaryCredential || '-')
    },
  },
  {
    title: '积分剩余',
    key: 'creditRemain',
    width: 140,
    render: (row: AccountRow) => {
      if (!row.credit || !Array.isArray(row.credit.packages)) return '-'
      const remain = row.credit.packages.reduce((s, p) => s + (p.cycleCapacityRemain || 0), 0)
      const size = row.credit.packages.reduce((s, p) => s + (p.cycleCapacitySize || 0), 0)
      return `${remain.toFixed(1)} / ${size.toFixed(0)}`
    },
  },
  {
    title: '本期消耗',
    key: 'usage',
    width: 110,
    render: (row: AccountRow) => {
      if (!row.usage) return '-'
      return `${row.usage.total.toFixed(2)}积分`
    },
  },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 180,
    render: (row) => formatTime(row.updatedAt),
  },
]

async function loadAccounts() {
  try {
    const res = await fetch('/api/accounts')
    if (!res.ok) throw new Error(`加载失败: ${res.status}`)
    const body = await res.json()
    tableData.value = (body.data || []).map((it: any) => {
      const uid = it.uid || '-'
      const extra = uid !== '-' ? getAccountExtra(uid) : {}
      return {
        id: it.id,
        uid,
        nickname: extractNickname(it.accountJson),
        primaryCredential: pickPrimaryCredential(it.apiKey, it.accessToken),
        apiKey: it.apiKey || null,
        accessToken: it.accessToken || null,
        updatedAt: it.updatedAt,
        credit: extra.credit,
        usage: extra.usage,
      }
    })
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(msg)
  }
}

async function refreshExtras() {
  if (tableData.value.length === 0) {
    await loadAccounts()
    return
  }
  const tasks = tableData.value
    .filter((row) => row.uid && row.uid !== '-')
    .map(async (row) => {
      await Promise.allSettled([
        fetchAndStore(row.uid, 'credit', `/api/billing/${row.uid}/user-resource`),
        fetchAndStore(row.uid, 'usage', `/api/billing/${row.uid}/user-request-usage`, {
          startTime: '2026-06-23 00:00:00',
          endTime: '2026-06-30 23:59:59',
          pageNum: 1,
          pageSize: 50,
        }),
      ])
    })
  await Promise.allSettled(tasks)
  await loadAccounts()
  message.success('积分、用量信息已刷新')
}

async function fetchAndStore(
  uid: string,
  key: 'credit' | 'usage',
  url: string,
  bodyObj?: Record<string, unknown>
) {
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(bodyObj ?? {}),
    })
    if (!res.ok) return
    const body = await res.json()
    const snapshot = body?.extra?.[key]
    if (snapshot && typeof snapshot === 'object') {
      mergeAccountExtra(uid, key, snapshot)
    }
  } catch (e) {
    console.warn(`刷新 ${uid}的 ${key}失败:`, e)
  }
}

function extractNickname(json: string): string {
  if (!json) return '未定义'
  try {
    const obj = JSON.parse(json)
    return obj?.account?.nickname || obj?.accounts?.[0]?.nickname || '未定义'
  } catch {
    return '未定义'
  }
}

function pickPrimaryCredential(apiKey?: string | null, accessToken?: string | null): string {
  if (apiKey && apiKey.trim()) return `APIKey ${maskToken(apiKey)}`
  if (accessToken && accessToken.trim()) return `Access ${maskToken(accessToken)}`
  return '-'
}

function maskToken(token: string): string {
  if (!token) return '-'
  if (token.length <= 12) return token
  return token.slice(0, 6) + '…' + token.slice(-4)
}

function formatTime(ts: number | null | undefined): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

// ========添加账户 ========

function openChooser() {
  showChooser.value = true
  detailInfo.value = null
  apiKeyForm.value = { nickname: '', apiKey: '' }
}

async function loadFromLocal() {
  showChooser.value = false
  showDetail.value = true
  loadingDetail.value = true
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
    } else if (res.status === 409) {
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
    } else if (res.status === 409) {
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
        <n-button type="primary" @click="openChooser">
          <template #icon>
            <Icon name="plus" :size="14" />
          </template>
          添加账户
        </n-button>
      </div>
    </div>

    <n-card :bordered="false" class="table-card">
      <n-data-table :columns="columns" :data="tableData" :bordered="false" :single-line="false" size="small"
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
  padding: 14px16px;
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
  padding: 2px4px;
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
