<script setup lang="ts">
defineProps<{
  name: 'user' | 'home' | 'settings' | 'logout' | 'plus' | 'refresh' | 'search' | 'eye' | 'eye-off' | 'logo'
  size?: number | string
  strokeWidth?: number | string
}>()
</script>

<template>
  <svg
    :width="size ?? 18"
    :height="size ?? 18"
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="strokeWidth ?? 1.6"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- 用户 -->
    <g v-if="name === 'user'">
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c0-4 4-6 8-6s8 2 8 6" />
    </g>

    <!-- 首页 -->
    <g v-else-if="name === 'home'">
      <path d="M3 11l9-7 9 7" />
      <path d="M5 10v10h14V10" />
    </g>

    <!-- 设置 -->
    <g v-else-if="name === 'settings'">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h.1a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8v.1a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" />
    </g>

    <!-- 退出 -->
    <g v-else-if="name === 'logout'">
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <polyline points="16 17 21 12 16 7" />
      <line x1="21" y1="12" x2="9" y2="12" />
    </g>

    <!-- 加号 -->
    <g v-else-if="name === 'plus'">
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </g>

    <!-- 刷新 -->
    <g v-else-if="name === 'refresh'">
      <polyline points="23 4 23 10 17 10" />
      <polyline points="1 20 1 14 7 14" />
      <path d="M3.5 9a9 9 0 0 1 14.8-3.4L23 10" />
      <path d="M20.5 15a9 9 0 0 1-14.8 3.4L1 14" />
    </g>

    <!-- 搜索 -->
    <g v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="7" />
      <line x1="21" y1="21" x2="16.6" y2="16.6" />
    </g>

    <!-- 眼睛（显示） -->
    <g v-else-if="name === 'eye'">
      <path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12z" />
      <circle cx="12" cy="12" r="3" />
    </g>

    <!-- 眼睛（隐藏） -->
    <g v-else-if="name === 'eye-off'">
      <path d="M17.9 17.9A10.7 10.7 0 0 1 12 19c-7 0-11-7-11-7a18.5 18.5 0 0 1 4.1-5.1" />
      <path d="M9.9 4.2A10.7 10.7 0 0 1 12 4c7 0 11 7 11 7a18.6 18.6 0 0 1-3.1 4.1" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </g>

    <!-- Logo -->
    <g v-else-if="name === 'logo'">
      <rect x="3" y="3" width="18" height="18" rx="3" />
      <path d="M8 12h8" />
      <path d="M12 8v8" />
    </g>
  </svg>
</template>
