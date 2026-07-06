<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { NMessageProvider } from 'naive-ui'
import AppLayout from './components/AppLayout.vue'

const route = useRoute()
/** 登录页不包裹 AppLayout */
const isPublic = computed(() => route.meta?.public === true)
</script>

<template>
  <n-message-provider>
    <template v-if="isPublic">
      <router-view />
    </template>
    <template v-else>
      <AppLayout>
        <router-view />
      </AppLayout>
    </template>
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
