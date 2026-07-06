<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NDropdown } from 'naive-ui'
import Icon from './Icon.vue'
import { clearToken, authFetch } from '../utils/auth'

interface NavItem {
  key: string
  label: string
  icon: 'home' | 'user' | 'settings' | 'key'
  disabled?: boolean
  to: string
}

const navItems: NavItem[] = [
  { key: 'overview', label: '总览', icon: 'home', to: '/overview' },
  { key: 'accounts', label: '上游账号管理', icon: 'user', to: '/accounts' },
  { key: 'downstream-keys', label: '下游账号管理', icon: 'key', to: '/downstream-keys' },
  { key: 'settings', label: '系统设置', icon: 'settings', to: '/settings' },
]

const route = useRoute()
const router = useRouter()

const activeKey = computed<string>(() => (route.name as string) ?? 'accounts')

const crumbTitle = computed<string>(() => (route.meta?.title as string) ?? '账号管理')
const crumbSub = computed<string>(() => (route.meta?.sub as string) ?? '')

function go(item: NavItem) {
  if (item.disabled) return
  router.push(item.to)
}

// ===== 登出 =====
const userMenuOptions = [
  { label: '登出', key: 'logout' },
]

async function handleUserMenuSelect(key: string) {
  if (key === 'logout') {
    try {
      await authFetch('/api/auth/logout', { method: 'POST' })
    } catch { /* 忽略网络错误，仍然清 token */ }
    clearToken()
    router.replace('/login')
  }
}
</script>

<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">
          <Icon name="logo" :size="20" />
        </div>
        <span class="brand-text">AgentreProxy</span>
      </div>

      <nav class="nav">
        <button
          v-for="item in navItems"
          :key="item.key"
          class="nav-item"
          :class="{
            'is-active': activeKey === item.key,
            'is-disabled': item.disabled,
          }"
          :disabled="item.disabled"
          @click="go(item)"
        >
          <Icon :name="item.icon" :size="18" />
          <span class="nav-label">{{ item.label }}</span>
          <span v-if="item.disabled" class="nav-tag">稍后</span>
        </button>
      </nav>
    </aside>

    <!-- 主区 -->
    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-title">{{ crumbTitle }}</span>
          <span class="crumb-sub">{{ crumbSub }}</span>
        </div>
        <div class="topbar-right">
          <n-dropdown :options="userMenuOptions" trigger="click" @select="handleUserMenuSelect">
            <span class="user-chip user-chip-clickable">
              管理员
              <Icon name="settings" :size="12" />
            </span>
          </n-dropdown>
        </div>
      </header>
      <section class="content">
        <slot />
      </section>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  background: #f7f8fa;
  color: #1f2329;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
}

/* Sidebar */
.sidebar {
  width: 220px;
  flex-shrink: 0;
  background: #ffffff;
  border-right: 1px solid #ececf0;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 18px;
  border-bottom: 1px solid #f0f0f3;
}
.brand-mark {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: #1f2329;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-text {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.2px;
}

.nav {
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  overflow-y: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: none;
  background: transparent;
  color: #4e5969;
  font-size: 14px;
  border-radius: 6px;
  cursor: pointer;
  text-align: left;
  width: 100%;
  position: relative;
  transition: background 0.12s ease, color 0.12s ease;
}
.nav-item:hover:not(:disabled) {
  background: #f2f3f5;
  color: #1f2329;
}
.nav-item.is-active {
  background: #eef0f3;
  color: #1f2329;
  font-weight: 500;
}
.nav-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: #1f2329;
  border-radius: 0 2px 2px 0;
}
.nav-item.is-disabled {
  cursor: not-allowed;
  color: #c9cdd4;
}
.nav-label {
  flex: 1;
}
.nav-tag {
  font-size: 11px;
  color: #8c8c8c;
  background: #f2f3f5;
  padding: 1px 6px;
  border-radius: 3px;
}

/* Main */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.topbar {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: #ffffff;
  border-bottom: 1px solid #ececf0;
}
.crumb {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.crumb-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2329;
}
.crumb-sub {
  font-size: 12px;
  color: #8c8c8c;
}
.user-chip {
  font-size: 13px;
  color: #4e5969;
  padding: 4px 10px;
  border: 1px solid #ececf0;
  border-radius: 4px;
  background: #fafbfc;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.user-chip-clickable {
  cursor: pointer;
  transition: background 0.12s ease, border-color 0.12s ease;
}
.user-chip-clickable:hover {
  background: #f2f3f5;
  border-color: #d9dadd;
}

.content {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}
</style>
