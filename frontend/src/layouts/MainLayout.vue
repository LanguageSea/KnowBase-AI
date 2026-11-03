<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  BookOpenText,
  ChevronLeft,
  LayoutDashboard,
  LibraryBig,
  LogOut,
  Menu,
  MessageSquareText,
  Moon,
  Presentation,
  Search,
  Settings,
  Sun,
} from 'lucide-vue-next'
import { authApi } from '../api'
import { clearSession, getCachedUser, setCachedUser } from '../utils/auth'
import { applyTheme, getTheme } from '../utils/theme'
import { notifyError } from '../utils/notify'

defineProps({
  eyebrow: { type: String, default: '' },
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  hideContentPadding: { type: Boolean, default: false },
})

const route = useRoute()
const router = useRouter()
const user = ref(getCachedUser())
const collapsed = ref(false)
const mobileOpen = ref(false)
const dark = ref(false)
const searchKeyword = ref('')
const loggingOut = ref(false)

try {
  collapsed.value = localStorage.getItem('kb_sidebar_collapsed') === '1'
} catch (e) { /* ignore */ }

const isSystemAdmin = computed(() => user.value?.roles?.includes('SYS_ADMIN'))
const displayName = computed(() => user.value?.nickname || user.value?.username || '当前用户')
const initials = computed(() => displayName.value.slice(0, 1).toUpperCase())

const navGroups = computed(() => {
  const groups = [
    {
      label: '工作区',
      items: [
        { name: 'dashboard', label: '工作台', icon: LayoutDashboard, match: ['dashboard'] },
        { name: 'workspace', label: '智能问答', icon: MessageSquareText, match: ['workspace'] },
      ],
    },
    {
      label: '知识库管理',
      items: [
        {
          name: 'knowledge-bases',
          label: '知识库',
          icon: LibraryBig,
          match: ['knowledge-bases', 'knowledge-base-detail', 'document-detail'],
        },
      ],
    },
  ]
  if (isSystemAdmin.value) {
    groups.push({
      label: '系统管理',
      items: [{ name: 'admin-settings', label: '系统设置', icon: Settings, match: ['admin-settings'] }],
    })
  }
  groups.push({
    label: '快捷入口',
    items: [{ name: 'project-showcase', label: '项目展示', icon: Presentation, match: ['project-showcase'] }],
  })
  return groups
})

const crumbs = computed(() => {
  const map = {
    dashboard: ['工作台'],
    workspace: ['AI 智能中心', '智能问答'],
    'knowledge-bases': ['知识库管理', '知识库'],
    'knowledge-base-detail': ['知识库管理', '知识库详情'],
    'document-detail': ['知识库管理', '文档详情'],
    'admin-settings': ['系统管理', '系统设置'],
    'project-showcase': ['项目展示'],
    login: ['登录'],
  }
  return map[route.name] || ['控制台']
})

function syncTheme() {
  dark.value = getTheme() === 'dark'
}

function toggleTheme() {
  dark.value = !dark.value
  applyTheme(dark.value ? 'dark' : 'light')
}

function toggleSidebar() {
  collapsed.value = !collapsed.value
  try {
    localStorage.setItem('kb_sidebar_collapsed', collapsed.value ? '1' : '0')
  } catch (e) { /* ignore */ }
}

function go(name) {
  mobileOpen.value = false
  if (route.name !== name) router.push({ name })
}

function submitSearch() {
  const keyword = searchKeyword.value.trim()
  mobileOpen.value = false
  router.push({ name: 'knowledge-bases', query: keyword ? { keyword } : {} })
}

async function loadMe() {
  try {
    user.value = await authApi.me()
    setCachedUser(user.value)
  } catch (err) {
    if (err.httpStatus !== 401) notifyError(err)
  }
}

async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await authApi.logout()
  } catch (err) {
    if (err.httpStatus !== 401) notifyError(err)
  } finally {
    clearSession()
    loggingOut.value = false
    mobileOpen.value = false
    router.replace({ name: 'login' })
  }
}

watch(() => route.fullPath, () => { mobileOpen.value = false })
onMounted(() => { syncTheme(); loadMe() })
</script>

<template>
  <div class="kb-shell" :class="{ collapsed }">
    <button
      v-if="mobileOpen"
      type="button"
      class="kb-scrim"
      aria-label="关闭菜单"
      @click="mobileOpen = false"
    />
    <aside class="kb-sidebar" :class="{ open: mobileOpen }" aria-label="主导航">
      <button type="button" class="kb-brand" @click="go('workspace')" aria-label="返回智能问答">
        <span class="kb-brand-mark"><BookOpenText :size="17" aria-hidden="true" /></span>
        <span v-if="!collapsed" class="kb-brand-copy">
          <span class="kb-brand-name">知库 AI</span>
          <span class="kb-brand-sub">KnowBase AI</span>
        </span>
      </button>
      <nav class="kb-nav kb-scroll">
        <div v-for="group in navGroups" :key="group.label" class="kb-nav-group">
          <span v-if="!collapsed" class="kb-nav-label">{{ group.label }}</span>
          <RouterLink
            v-for="item in group.items"
            :key="item.name"
            class="kb-nav-item"
            :class="{ active: item.match.includes(route.name) }"
            :title="collapsed ? item.label : ''"
            :to="{ name: item.name }"
            @click="mobileOpen = false"
          >
            <component :is="item.icon" :size="17" aria-hidden="true" />
            <span v-if="!collapsed">{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>
      <div class="kb-side-foot">
        <button
          type="button"
          class="kb-collapse"
          :aria-label="collapsed ? '展开导航' : '收起导航'"
          @click="toggleSidebar"
        >
          <ChevronLeft :size="16" :class="{ flip: collapsed }" aria-hidden="true" />
          <span v-if="!collapsed">收起</span>
        </button>
        <div v-if="!collapsed" class="kb-user">
          <span class="kb-avatar" aria-hidden="true">{{ initials }}</span>
          <span class="kb-user-copy">
            <span class="kb-user-name">{{ displayName }}</span>
            <span v-if="user?.roles?.length" class="kb-user-role">{{ user.roles.join(' / ') }}</span>
          </span>
          <button type="button" class="kb-logout" title="退出登录" aria-label="退出登录" :disabled="loggingOut" @click="logout">
            <LogOut :size="15" aria-hidden="true" />
          </button>
        </div>
      </div>
    </aside>

    <div class="kb-main">
      <header class="kb-topbar">
        <button type="button" class="kb-icon-btn kb-mobile-toggle" aria-label="打开菜单" :aria-expanded="mobileOpen" @click="mobileOpen = !mobileOpen">
          <Menu :size="18" aria-hidden="true" />
        </button>
        <nav class="kb-crumbs" aria-label="面包屑">
          <span v-for="(crumb, index) in crumbs" :key="crumb" class="kb-crumb">
            <span v-if="index > 0" class="kb-crumb-sep">/</span>
            <span :class="{ current: index === crumbs.length - 1 }">{{ crumb }}</span>
          </span>
        </nav>
        <form class="kb-search" role="search" @submit.prevent="submitSearch">
          <Search :size="15" aria-hidden="true" />
          <input v-model="searchKeyword" type="search" placeholder="搜索知识库…" aria-label="全局搜索" />
        </form>
        <div class="kb-top-actions">
          <button type="button" class="kb-icon-btn" :aria-label="dark ? '切换浅色主题' : '切换深色主题'" title="主题切换" @click="toggleTheme">
            <Moon v-if="!dark" :size="17" aria-hidden="true" />
            <Sun v-else :size="17" aria-hidden="true" />
          </button>
          <span class="kb-top-user">
            <span class="kb-avatar sm" aria-hidden="true">{{ initials }}</span>
            <span class="kb-top-name">{{ displayName }}</span>
            <button type="button" class="kb-icon-btn danger" title="退出登录" aria-label="退出登录" :disabled="loggingOut" @click="logout">
              <LogOut :size="15" aria-hidden="true" />
            </button>
          </span>
        </div>
      </header>
      <nav v-if="mobileOpen" class="mobile-panel" aria-label="移动导航">
        <div v-for="group in navGroups" :key="group.label">
          <p class="mobile-group">{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.name"
            class="mobile-link"
            :class="{ active: item.match.includes(route.name) }"
            :to="{ name: item.name }"
          >
            <component :is="item.icon" :size="17" aria-hidden="true" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>

      <main class="kb-content kb-scroll" :class="{ flush: hideContentPadding }">
        <section v-if="title" class="kb-page-head">
          <div>
            <p v-if="eyebrow" class="kb-eyebrow">{{ eyebrow }}</p>
            <h1>{{ title }}</h1>
            <p v-if="subtitle" class="kb-subtitle">{{ subtitle }}</p>
          </div>
          <div class="kb-page-actions"><slot name="actions" /></div>
        </section>
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.kb-shell { display: flex; height: 100vh; height: 100dvh; overflow: hidden; background: var(--kb-bg); }
.kb-sidebar { width: 220px; flex: none; display: flex; flex-direction: column; background: var(--kb-sidebar); color: var(--kb-sidebar-text); border-right: 1px solid var(--kb-sidebar-border); transition: width 0.18s ease; z-index: 60; }
.kb-shell.collapsed .kb-sidebar { width: 64px; }
.kb-brand { display: flex; align-items: center; gap: 10px; min-height: 60px; padding: 0 14px; color: inherit; background: none; border: 0; border-bottom: 1px solid rgba(255,255,255,0.07); cursor: pointer; text-align: left; }
.kb-brand-mark { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; flex: none; color: #fff; background: linear-gradient(135deg, #4f46e5, #2f6fed); border-radius: 8px; }
.kb-brand-copy { display: flex; flex-direction: column; line-height: 1.15; min-width: 0; }
.kb-brand-name { font-size: 14px; font-weight: 700; color: #fff; white-space: nowrap; }
.kb-brand-sub { font-size: 9px; letter-spacing: 0.6px; text-transform: uppercase; color: var(--kb-sidebar-muted); }
.kb-nav { flex: 1; min-height: 0; overflow-y: auto; padding: 12px 10px; display: flex; flex-direction: column; gap: 14px; }
.kb-nav-label { display: block; padding: 0 8px 6px; font-size: 11px; color: var(--kb-sidebar-muted); letter-spacing: 0.4px; }
.kb-nav-item { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 38px; padding: 0 10px; color: var(--kb-sidebar-text); background: transparent; border: 0; border-radius: 8px; font: inherit; font-size: 13px; cursor: pointer; text-decoration: none; transition: background 0.14s, color 0.14s; }
.kb-shell.collapsed .kb-nav-item { justify-content: center; padding: 0; }
.kb-nav-item:hover { background: var(--kb-sidebar-hover); color: #fff; }
.kb-nav-item.active { background: var(--kb-sidebar-active); color: #fff; font-weight: 600; box-shadow: inset 2px 0 0 var(--kb-primary); }
.kb-side-foot { padding: 10px; border-top: 1px solid rgba(255,255,255,0.07); display: grid; gap: 8px; }
.kb-collapse { display: flex; align-items: center; gap: 6px; justify-content: center; min-height: 32px; color: var(--kb-sidebar-muted); background: transparent; border: 1px solid rgba(255,255,255,0.1); border-radius: 7px; font: inherit; font-size: 12px; cursor: pointer; }
.kb-collapse:hover { color: #fff; border-color: rgba(255,255,255,0.22); }
.kb-collapse .flip { transform: rotate(180deg); }
.kb-user { display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 8px; padding: 8px; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.08); border-radius: 8px; }
.kb-avatar { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; flex: none; color: #dfe7ff; background: #3b4a68; border-radius: 50%; font-size: 12px; font-weight: 700; }
.kb-avatar.sm { width: 28px; height: 28px; font-size: 11px; color: #31418f; background: #e3e9fd; border: 1px solid #c9d4f8; }
.kb-user-copy { display: flex; flex-direction: column; min-width: 0; line-height: 1.25; }
.kb-user-name { overflow: hidden; font-size: 12px; font-weight: 600; color: #fff; text-overflow: ellipsis; white-space: nowrap; }
.kb-user-role { overflow: hidden; font-size: 10px; color: var(--kb-sidebar-muted); text-overflow: ellipsis; white-space: nowrap; }
.kb-logout { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; color: var(--kb-sidebar-muted); background: transparent; border: 1px solid transparent; border-radius: 6px; cursor: pointer; }
.kb-logout:hover { color: #ffb4ae; border-color: rgba(255,255,255,0.16); }
.kb-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.kb-topbar { display: flex; align-items: center; gap: 14px; min-height: 60px; flex: none; padding: 0 20px; background: var(--kb-surface); border-bottom: 1px solid var(--kb-border); }
.kb-crumbs { display: flex; align-items: center; gap: 6px; min-width: 0; overflow: hidden; white-space: nowrap; font-size: 13px; color: var(--kb-text-muted); }
.kb-crumb .current { color: var(--kb-text); font-weight: 600; }
.kb-crumb-sep { margin-right: 6px; color: var(--kb-border-strong); }
.kb-search { display: flex; align-items: center; gap: 8px; width: min(320px, 32vw); margin-left: auto; padding: 0 12px; height: 36px; color: var(--kb-text-muted); background: var(--kb-bg); border: 1px solid var(--kb-border); border-radius: 8px; }
.kb-search:focus-within { border-color: var(--kb-primary); box-shadow: 0 0 0 3px var(--kb-primary-ring); color: var(--kb-primary); }
.kb-search input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--kb-text); font: inherit; font-size: 13px; }
.kb-top-actions { display: flex; align-items: center; gap: 8px; flex: none; }
.kb-icon-btn { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; color: var(--kb-text-secondary); background: transparent; border: 1px solid transparent; border-radius: 8px; cursor: pointer; }
.kb-icon-btn:hover { background: var(--kb-bg); border-color: var(--kb-border); color: var(--kb-text); }
.kb-icon-btn.danger:hover { color: #b3261e; background: #fdf0ef; border-color: #f2c6c1; }
.kb-top-user { display: flex; align-items: center; gap: 8px; padding-left: 8px; border-left: 1px solid var(--kb-border); }
.kb-top-name { max-width: 140px; overflow: hidden; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.kb-mobile-toggle, .kb-scrim, .mobile-panel { display: none; }
.kb-content { flex: 1; min-height: 0; overflow-y: auto; padding: 26px 28px 48px; }
.kb-content.flush { padding: 14px; display: flex; flex-direction: column; overflow: hidden; }
.kb-page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.kb-page-head h1 { font-size: 24px; font-weight: 700; letter-spacing: -0.2px; }
.kb-eyebrow { margin-bottom: 4px; color: var(--kb-primary); font-size: 12px; font-weight: 700; letter-spacing: 0.4px; }
.kb-subtitle { margin-top: 5px; color: var(--kb-text-secondary); font-size: 13px; }
.kb-page-actions { display: flex; gap: 8px; flex: none; }
@media (max-width: 1080px) { .kb-search { width: 220px; } .kb-top-name { display: none; } }
@media (max-width: 860px) {
  .kb-sidebar { position: fixed; top: 0; bottom: 0; left: 0; width: 248px !important; transform: translateX(-102%); transition: transform 0.2s ease; box-shadow: 24px 0 48px rgba(15,23,42,0.22); }
  .kb-sidebar.open { transform: none; }
  .kb-collapse { display: none; }
  .kb-mobile-toggle { display: inline-flex; }
  .kb-scrim { display: block; position: fixed; inset: 0; z-index: 55; background: rgba(15,23,42,0.42); border: 0; }
  .mobile-panel { display: grid; gap: 4px; padding: 12px 16px 16px; background: var(--kb-surface); border-bottom: 1px solid var(--kb-border); }
  .mobile-group { margin: 8px 0 4px; font-size: 11px; font-weight: 700; color: var(--kb-text-muted); letter-spacing: 0.4px; }
  .mobile-link { display: flex; align-items: center; gap: 10px; min-height: 42px; padding: 0 12px; border-radius: 8px; color: var(--kb-text-secondary); text-decoration: none; font-size: 14px; }
  .mobile-link.active { color: var(--kb-primary-strong); background: var(--kb-primary-soft); font-weight: 600; }
  .kb-content { padding: 18px 14px 40px; }
  .kb-search { display: none; }
  .kb-page-head { flex-direction: column; align-items: stretch; }
}
@media (prefers-reduced-motion: reduce) { .kb-sidebar, .kb-nav-item { transition: none; } }
</style>
