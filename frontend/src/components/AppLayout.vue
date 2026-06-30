<script setup lang="ts">
import { ref } from 'vue'
import Icon from './Icon.vue'

interface NavItem {
  key: string
  label: string
  icon: 'home' | 'user' | 'settings'
  disabled?: boolean
}

const navItems: NavItem[] = [
  { key: 'accounts', label: '账号管理', icon: 'user' },
  { key: 'overview', label: '概览', icon: 'home', disabled: true },
  { key: 'settings', label: '系统设置', icon: 'settings', disabled: true },
]

const activeKey = ref<string>('accounts')
const collapsed = ref(false)

function selectItem(item: NavItem) {
  if (item.disabled) return
  activeKey.value = item.key
  emit('change', item.key)
}

const emit = defineEmits<{
  (e: 'change', key: string): void
}>()
</script>

<template>
  <div class="layout" :class="{ 'is-collapsed': collapsed }">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">
          <Icon name="logo" :size="20" />
        </div>
        <span v-show="!collapsed" class="brand-text">AgentreProxy</span>
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
          @click="selectItem(item)"
        >
          <Icon :name="item.icon" :size="18" />
          <span v-show="!collapsed" class="nav-label">{{ item.label }}</span>
          <span v-if="!collapsed && item.disabled" class="nav-tag">稍后</span>
        </button>
      </nav>

      <div class="sidebar-foot">
        <button class="collapse-btn" @click="collapsed = !collapsed" :title="collapsed ? '展开' : '收起'">
          <Icon name="logout" :size="16" :style="{ transform: collapsed ? 'rotate(180deg)' : '' }" />
        </button>
      </div>
    </aside>

    <!-- 主区 -->
    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-title">账号管理</span>
          <span class="crumb-sub">管理 CodeBuddy 账户与凭证</span>
        </div>
        <div class="topbar-right">
          <span class="user-chip">管理员</span>
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
  transition: width 0.2s ease;
}
.is-collapsed .sidebar {
  width: 64px;
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

.sidebar-foot {
  padding: 12px;
  border-top: 1px solid #f0f0f3;
}
.collapse-btn {
  width: 100%;
  height: 32px;
  border: 1px solid #ececf0;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #4e5969;
  transition: background 0.12s ease;
}
.collapse-btn:hover {
  background: #f2f3f5;
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
}

.content {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}
</style>
