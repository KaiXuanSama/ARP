import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import AccountManagement from './components/AccountManagement.vue'
import Overview from './components/Overview.vue'
import Settings from './components/Settings.vue'
import DownstreamKeyManagement from './components/DownstreamKeyManagement.vue'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/accounts' },
  { path: '/overview', name: 'overview', component: Overview, meta: { title: '总览', sub: '系统运行总览' } },
  { path: '/accounts', name: 'accounts', component: AccountManagement, meta: { title: '账号管理', sub: '管理 CodeBuddy 账户与凭证' } },
  { path: '/downstream-keys', name: 'downstream-keys', component: DownstreamKeyManagement, meta: { title: '下游账号管理', sub: '管理下游用户调 chat 用的 API Key' } },
  { path: '/settings', name: 'settings', component: Settings, meta: { title: '系统设置', sub: '全局偏好与运行环境配置' } },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

export default router
