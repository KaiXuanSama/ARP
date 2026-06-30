<script setup lang="ts">
import { NMessageProvider } from 'naive-ui'
import Home from './components/Home.vue'
</script>

<template>
  <n-message-provider>
    <Home />
  </n-message-provider>
</template>
