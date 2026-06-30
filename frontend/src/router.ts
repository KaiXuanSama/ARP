import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import AccountManagement from './components/AccountManagement.vue'
import Overview from './components/Overview.vue'
import Settings from './components/Settings.vue'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/accounts' },
  { path: '/overview', name: 'overview', component: Overview, meta: { title: '概览', sub: '系统运行总览' } },
  { path: '/accounts', name: 'accounts', component: AccountManagement, meta: { title: '账号管理', sub: '管理 CodeBuddy 账户与凭证' } },
  { path: '/settings', name: 'settings', component: Settings, meta: { title: '系统设置', sub: '全局偏好与运行配置' } },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

export default router
