<script setup lang="ts">
import Icon from './Icon.vue'
</script>

<template>
  <div class="placeholder">
    <div class="placeholder-icon">
      <Icon name="home" :size="32" />
    </div>
    <h2 class="placeholder-title">概览</h2>
    <p class="placeholder-desc">此处将展示系统的整体运行状态、活跃账户数量与近期活动日志。</p>
    <span class="placeholder-tag">占位页面</span>
  </div>
</template>

<style scoped>
.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 56px - 48px);
  gap: 12px;
  text-align: center;
}
.placeholder-icon {
  width: 64px;
  height: 64px;
  border-radius: 12px;
  background: #f2f3f5;
  color: #4e5969;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
}
.placeholder-title {
  font-size: 18px;
  font-weight: 600;
  color: #1f2329;
  margin: 0;
}
.placeholder-desc {
  font-size: 13px;
  color: #8c8c8c;
  max-width: 480px;
  margin: 0;
  line-height: 1.6;
}
.placeholder-tag {
  margin-top: 8px;
  font-size: 12px;
  color: #8c8c8c;
  background: #f2f3f5;
  padding: 2px 10px;
  border-radius: 3px;
}
</style>
