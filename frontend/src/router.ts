import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import AccountManagement from './components/AccountManagement.vue'
import Overview from './components/Overview.vue'
import Settings from './components/Settings.vue'
import DownstreamKeyManagement from './components/DownstreamKeyManagement.vue'
import Login from './components/Login.vue'
import { hasToken } from './utils/auth'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: Login, meta: { title: '登录', public: true } },
  { path: '/', redirect: '/overview' },
  { path: '/overview', name: 'overview', component: Overview, meta: { title: '总览', sub: '系统运行总览' } },
  { path: '/accounts', name: 'accounts', component: AccountManagement, meta: { title: '账号管理', sub: '管理 CodeBuddy 账户与凭证' } },
  { path: '/downstream-keys', name: 'downstream-keys', component: DownstreamKeyManagement, meta: { title: '下游账号管理', sub: '管理下游用户调 chat 用的 API Key' } },
  { path: '/settings', name: 'settings', component: Settings, meta: { title: '系统设置', sub: '全局偏好与运行环境配置' } },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

/**
 * 路由守卫 — 无 token 时重定向到登录页
 * <p>
 * 登录页本身标记 meta.public=true，不拦截。
 * 已有 token 访问登录页 → 直接跳首页。
 */
router.beforeEach((to, _from, next) => {
  if (to.meta?.public) {
    // 已登录用户访问登录页 → 跳首页
    if (to.name === 'login' && hasToken()) {
      next('/')
    } else {
      next()
    }
    return
  }
  if (!hasToken()) {
    next('/login')
    return
  }
  next()
})

export default router
