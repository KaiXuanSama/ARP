<script setup lang="ts">
/**
 * 登录页 — 简洁的居中卡片式登录表单
 * <p>
 * 风格与管理面板一致（白底卡片 + 圆角 + 细边框）。
 * 登录成功后存 token 到 localStorage 并跳转到首页。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NInput, NButton, useMessage } from 'naive-ui'
import { setToken } from '../utils/auth'
import Icon from './Icon.vue'

const router = useRouter()
const message = useMessage()

const username = ref('')
const password = ref('')
const loading = ref(false)

async function handleLogin() {
  if (loading.value) return
  if (!username.value.trim()) {
    message.warning('请输入用户名')
    return
  }
  if (!password.value) {
    message.warning('请输入密码')
    return
  }

  loading.value = true
  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: username.value.trim(),
        password: password.value,
      }),
    })
    if (!res.ok) {
      const body = await res.json().catch(() => ({}))
      throw new Error(body?.message || `登录失败: ${res.status}`)
    }
    const body = await res.json()
    if (!body.token) {
      throw new Error('服务端未返回 token')
    }
    setToken(body.token)
    message.success('登录成功')
    router.replace('/')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(msg)
  } finally {
    loading.value = false
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    handleLogin()
  }
}
</script>

<template>
  <div class="login-page" @keydown="handleKeydown">
    <div class="login-card">
      <div class="login-brand">
        <div class="login-brand-mark">
          <Icon name="logo" :size="22" />
        </div>
        <span class="login-brand-text">AgentreProxy</span>
      </div>
      <p class="login-subtitle">管理面板登录</p>

      <div class="login-form">
        <div class="login-field">
          <label class="login-label">用户名</label>
          <n-input
            v-model:value="username"
            placeholder="请输入用户名"
            clearable
            autofocus
          />
        </div>
        <div class="login-field">
          <label class="login-label">密码</label>
          <n-input
            v-model:value="password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码"
            clearable
          />
        </div>
        <n-button
          type="primary"
          block
          :loading="loading"
          @click="handleLogin"
          class="login-btn"
        >
          登录
        </n-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: #f7f8fa;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
}

.login-card {
  width: 380px;
  background: #ffffff;
  border: 1px solid #ececf0;
  border-radius: 10px;
  padding: 36px 32px 32px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.login-brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 7px;
  background: #1f2329;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-brand-text {
  font-size: 18px;
  font-weight: 700;
  color: #1f2329;
  letter-spacing: 0.2px;
}

.login-subtitle {
  margin: 6px 0 24px;
  font-size: 13px;
  color: #8c8c8c;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.login-label {
  font-size: 13px;
  font-weight: 500;
  color: #4e5969;
}

.login-btn {
  margin-top: 4px;
}
</style>
