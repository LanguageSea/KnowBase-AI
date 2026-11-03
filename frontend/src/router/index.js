import { createRouter, createWebHistory } from 'vue-router'
import { getCachedUser, getToken } from '../utils/auth'
import { setUnauthorizedHandler } from '../api/http'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录 · KnowBase AI' },
  },
  {
    path: '/',
    alias: '/project',
    name: 'project-showcase',
    component: () => import('../views/ProjectShowcaseView.vue'),
    meta: { title: '知库 AI · 项目展示' },
  },
  {
    path: '/knowledge-bases',
    name: 'knowledge-bases',
    component: () => import('../views/KnowledgeBaseListView.vue'),
    meta: { requiresAuth: true, title: '知识库 · KnowBase AI' },
  },
  {
    path: '/knowledge-bases/:kbId',
    name: 'knowledge-base-detail',
    component: () => import('../views/KnowledgeBaseDetailView.vue'),
    meta: { requiresAuth: true, title: '知识库详情 · KnowBase AI' },
  },
  {
    path: '/knowledge-bases/:kbId/documents/:docId',
    name: 'document-detail',
    component: () => import('../views/DocumentDetailView.vue'),
    meta: { requiresAuth: true, title: '文档详情 · KnowBase AI' },
  },
  {
    path: '/workspace',
    name: 'workspace',
    component: () => import('../views/WorkspaceView.vue'),
    meta: { requiresAuth: true, title: '智能问答 · KnowBase AI' },
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('../views/WorkspaceDashboardView.vue'),
    meta: { requiresAuth: true, title: '工作台 · KnowBase AI' },
  },
  {
    path: '/admin',
    name: 'admin-settings',
    component: () => import('../views/AdminUsersView.vue'),
    meta: {
      requiresAuth: true,
      requiresSystemAdmin: true,
      title: '系统设置 · KnowBase AI',
    },
  },
  { path: '/system/users', redirect: { name: 'admin-settings', query: { tab: 'users' } } },
  { path: '/:pathMatch(.*)*', redirect: { name: 'project-showcase' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    return { top: 0, left: 0 }
  },
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !getToken()) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : undefined }
  }
  if (to.meta.requiresSystemAdmin) {
    const user = getCachedUser()
    if (!user?.roles?.includes('SYS_ADMIN')) {
      return { name: 'knowledge-bases' }
    }
  }
  return true
})

router.afterEach((to) => {
  if (to.meta.title) document.title = to.meta.title
})

setUnauthorizedHandler(() => {
  if (router.currentRoute.value.name !== 'login') {
    router.replace({ name: 'login' })
  }
})

export default router
