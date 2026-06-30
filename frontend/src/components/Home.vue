<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NModal, NCard, NDescriptions, NDescriptionsItem, NSpin, NSpace, useMessage } from 'naive-ui'

interface WorkbuddyInfo {
  account: {
    uid: string
    nickname: string
    uin: string
    type: string
    pluginEnabled: boolean
    phoneNumber: string
  }
  auth: {
    accessToken: string
    tokenType: string
    scope: string
    domain: string
    expiresAt: number
    refreshExpiresAt: number
  }
}

const showModal = ref(false)
const loading = ref(false)
const saving = ref(false)
const info = ref<WorkbuddyInfo | null>(null)
const message = useMessage()

async function fetchInfo() {
  loading.value = true
  showModal.value = true
  try {
    const res = await fetch('/api/workbuddy-info')
    if (!res.ok) throw new Error(`请求失败: ${res.status}`)
    info.value = await res.json()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`获取信息失败: ${msg}`)
    showModal.value = false
  } finally {
    loading.value = false
  }
}

function handleCancel() {
  showModal.value = false
  info.value = null
}

async function handleSave() {
  if (!info.value) return
  saving.value = true
  try {
    const res = await fetch('/api/accounts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(info.value),
    })
    const body = await res.json().catch(() => ({}))
    if (res.ok) {
      const status = body.status as string
      const text = status === 'created' ? '新建成功' : status === 'updated' ? '已覆盖旧记录' : '保存成功'
      message.success(text)
      showModal.value = false
      info.value = null
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
    saving.value = false
  }
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleString('zh-CN')
}

function maskToken(token: string): string {
  if (token.length <= 20) return token
  return token.slice(0, 10) + '…' + token.slice(-10)
}
</script>

<template>
  <div class="app">
    <h1>AgentreProxy</h1>
    <p>Vue 3 + TypeScript + Vite</p>
    <n-button type="primary" size="large" @click="fetchInfo" style="margin-top: 24px">
      获取本机 CodeBuddy 信息
    </n-button>

    <n-modal v-model:show="showModal" preset="card" title="CodeBuddy 桌面端信息" style="width: 600px;">
      <n-spin :show="loading">
        <template v-if="info">
          <n-card title="账户信息" size="small" style="margin-bottom: 16px;">
            <n-descriptions label-placement="left" bordered :column="1">
              <n-descriptions-item label="昵称">{{ info.account.nickname }}</n-descriptions-item>
              <n-descriptions-item label="UID">{{ info.account.uid }}</n-descriptions-item>
              <n-descriptions-item label="UIN">{{ info.account.uin }}</n-descriptions-item>
              <n-descriptions-item label="类型">{{ info.account.type }}</n-descriptions-item>
              <n-descriptions-item label="手机号">{{ info.account.phoneNumber }}</n-descriptions-item>
              <n-descriptions-item label="插件启用">{{ info.account.pluginEnabled ? '是' : '否' }}</n-descriptions-item>
            </n-descriptions>
          </n-card>

          <n-card title="认证信息" size="small">
            <n-descriptions label-placement="left" bordered :column="1">
              <n-descriptions-item label="Token 类型">{{ info.auth.tokenType }}</n-descriptions-item>
              <n-descriptions-item label="Access Token">{{ maskToken(info.auth.accessToken) }}</n-descriptions-item>
              <n-descriptions-item label="域名">{{ info.auth.domain }}</n-descriptions-item>
              <n-descriptions-item label="作用域">{{ info.auth.scope }}</n-descriptions-item>
              <n-descriptions-item label="Token 过期时间">{{ formatTime(info.auth.expiresAt) }}</n-descriptions-item>
              <n-descriptions-item label="刷新 Token 过期">{{ formatTime(info.auth.refreshExpiresAt) }}</n-descriptions-item>
            </n-descriptions>
          </n-card>
        </template>
      </n-spin>

      <template #footer>
        <n-space justify="end">
          <n-button @click="handleCancel" :disabled="saving">取消</n-button>
          <n-button type="primary" :loading="saving" :disabled="!info || loading" @click="handleSave">
            保存
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  font-family: system-ui, -apple-system, sans-serif;
}

h1 {
  font-size: 2.5rem;
  color: #2c3e50;
}

p {
  color: #666;
}
</style>
