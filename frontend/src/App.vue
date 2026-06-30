<script setup lang="ts">
import { NMessageProvider } from 'naive-ui'
import AppLayout from './components/AppLayout.vue'
</script>

<template>
  <n-message-provider>
    <AppLayout>
      <router-view />
    </AppLayout>
  </n-message-provider>
</template>

<style>
* {
  box-sizing: border-box;
}
html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
}
</style>
