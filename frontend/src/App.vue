<script setup lang="ts">
import { ref } from 'vue'
import { NMessageProvider } from 'naive-ui'
import AppLayout from './components/AppLayout.vue'
import AccountManagement from './components/AccountManagement.vue'

const currentPage = ref<string>('accounts')
</script>

<template>
  <n-message-provider>
    <AppLayout @change="(key) => (currentPage = key)">
      <AccountManagement v-if="currentPage === 'accounts'" />
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
