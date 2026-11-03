<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  BookOpenText,
  LayoutDashboard,
  LibraryBig,
  LogOut,
  Menu,
  MessageSquareText,
  Presentation,
  Settings,
  X,
} from 'lucide-vue-next'
import { authApi } from '../api'
import { clearSession, getCachedUser, setCachedUser } from '../utils/auth'
import { notifyError } from '../utils/notify'

const route = useRoute()
const router = useRouter()
const user = ref(getCachedUser())
const loggingOut = ref(false)
const mobileOpen = ref(false)
const isSystemAdmin = computed(() => user.value?.roles?.includes('SYS_ADMIN'))
const displayName = computed(
  () => user.value?.nickname || user.value?.username || '当前用户',
)
const initials = computed(() => displayName.value.slice(0, 1).toUpperCase())
const navItems = computed(() => {
  const items = [
    {
      name: 'workspace',
      label: '智能问答',
      icon: MessageSquareText,
      active: () => route.name === 'workspace',
    },
    {
      name: 'knowledge-bases',
      label: '知识库',
      icon: LibraryBig,
      active: () =>
        ['knowledge-bases', 'knowledge-base-detail', 'document-detail'].includes(route.name),
    },
    {
      name: 'dashboard',
      label: '工作台',
      icon: LayoutDashboard,
      active: () => route.name === 'dashboard',
    },
    {
      name: 'project-showcase',
      label: '项目展示',
      icon: Presentation,
      active: () => route.name === 'project-showcase',
    },
  ]
  if (isSystemAdmin.value) {
    items.push({
      name: 'admin-settings',
      label: '系统设置',
      icon: Settings,
      active: () => route.name === 'admin-settings',
    })
  }
  return items
})

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

function goBrand() {
  router.push({ name: 'workspace' })
}

function closeMobile() {
  mobileOpen.value = false
}

function handleKeydown(event) {
  if (event.key === 'Escape') closeMobile()
}

watch(
  () => route.fullPath,
  () => closeMobile(),
)

onMounted(() => {
  loadMe()
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <header class="app-header">
    <button type="button" class="brand" aria-label="返回智能问答" @click="goBrand">
      <span class="brand-mark"><BookOpenText :size="17" aria-hidden="true" /></span>
      <span class="brand-copy">
        <span class="brand-name">知库 AI</span>
        <span class="brand-subtitle">KnowBase AI</span>
      </span>
    </button>

    <nav class="nav" aria-label="主导航">
      <RouterLink
        v-for="item in navItems"
        :key="item.name"
        class="nav-link"
        :class="{ active: item.active() }"
        :to="{ name: item.name }"
      >
        <component :is="item.icon" :size="15" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="account">
      <span class="account-avatar" aria-hidden="true">{{ initials }}</span>
      <span class="account-copy">
        <span class="account-name">{{ displayName }}</span>
        <span v-if="user?.roles?.length" class="account-role">{{ user.roles.join(' / ') }}</span>
      </span>
      <button
        type="button"
        class="logout-button"
        :disabled="loggingOut"
        title="退出登录"
        aria-label="退出登录"
        @click="logout"
      >
        <LogOut :size="16" aria-hidden="true" />
      </button>
    </div>

    <button
      type="button"
      class="mobile-toggle"
      :aria-expanded="mobileOpen"
      aria-controls="mobile-navigation"
      :aria-label="mobileOpen ? '关闭菜单' : '打开菜单'"
      @click="mobileOpen = !mobileOpen"
    >
      <X v-if="mobileOpen" :size="20" aria-hidden="true" />
      <Menu v-else :size="20" aria-hidden="true" />
    </button>

    <button
      v-if="mobileOpen"
      type="button"
      class="mobile-scrim"
      aria-label="关闭菜单"
      @click="closeMobile"
    />
    <nav v-if="mobileOpen" id="mobile-navigation" class="mobile-panel" aria-label="移动导航">
      <RouterLink
        v-for="item in navItems"
        :key="item.name"
        class="mobile-link"
        :class="{ active: item.active() }"
        :to="{ name: item.name }"
      >
        <component :is="item.icon" :size="18" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </RouterLink>
      <div class="mobile-account">
        <span class="account-avatar">{{ initials }}</span>
        <div>
          <strong>{{ displayName }}</strong>
          <span>{{ user?.roles?.join(' / ') || '普通用户' }}</span>
        </div>
        <button type="button" :disabled="loggingOut" @click="logout">
          <LogOut :size="15" aria-hidden="true" />
          退出
        </button>
      </div>
    </nav>
  </header>
</template>

<style scoped>
.app-header {
  position: relative;
  z-index: 50;
  flex: none;
  display: flex;
  align-items: center;
  gap: 24px;
  height: 60px;
  padding: 0 24px;
  background: rgba(255, 255, 255, 0.96);
  border-bottom: 1px solid var(--kb-border);
  box-shadow: 0 1px 0 rgba(31, 36, 48, 0.02);
  backdrop-filter: blur(12px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0;
  color: inherit;
  background: none;
  border: 0;
  cursor: pointer;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  color: #ffffff;
  background: #2469db;
  border-radius: 7px;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.1;
}

.brand-name {
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
}

.brand-subtitle {
  margin-top: 3px;
  color: var(--kb-text-muted);
  font-size: 9px;
  letter-spacing: 0.55px;
  text-transform: uppercase;
}

.nav {
  align-self: stretch;
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.nav::-webkit-scrollbar {
  display: none;
}

.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  color: #5d6575;
  border-radius: 6px;
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
  transition: color 0.15s, background 0.15s;
}

.nav-link:hover {
  color: var(--kb-text);
  background: #f2f4f8;
}

.nav-link.active {
  color: #1758c7;
  background: #eaf1fe;
  font-weight: 600;
}

.account {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-left: auto;
}

.account-avatar {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  color: #1758c7;
  background: #e7effd;
  border: 1px solid #d4e1f8;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 700;
}

.account-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.25;
}

.account-name {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-role {
  max-width: 160px;
  overflow: hidden;
  margin-top: 2px;
  color: var(--kb-text-muted);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logout-button,
.mobile-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  color: #6e7686;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
}

.logout-button:hover,
.mobile-toggle:hover {
  color: #b33a31;
  background: #fff5f3;
  border-color: #f1d8d4;
}

.logout-button:disabled {
  opacity: 0.45;
  cursor: wait;
}

.mobile-toggle,
.mobile-panel,
.mobile-scrim {
  display: none;
}

@media (max-width: 980px) {
  .app-header {
    gap: 12px;
    padding: 0 16px;
  }

  .nav {
    display: none;
  }

  .account {
    margin-left: auto;
  }

  .mobile-toggle {
    display: inline-flex;
  }

  .mobile-scrim {
    position: fixed;
    inset: 60px 0 0;
    display: block;
    padding: 0;
    background: rgba(18, 24, 34, 0.26);
    border: 0;
  }

  .mobile-panel {
    position: absolute;
    top: 60px;
    right: 0;
    left: 0;
    display: grid;
    gap: 4px;
    padding: 10px 16px 16px;
    background: #ffffff;
    border-bottom: 1px solid var(--kb-border);
    box-shadow: 0 14px 30px rgba(31, 36, 48, 0.12);
  }

  .mobile-link {
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 42px;
    padding: 0 12px;
    color: var(--kb-text-secondary);
    border-radius: 6px;
    font-size: 14px;
    text-decoration: none;
  }

  .mobile-link.active {
    color: #1758c7;
    background: #eaf1fe;
    font-weight: 600;
  }

  .mobile-account {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 10px;
    padding: 12px;
    margin-top: 6px;
    background: #f7f8fa;
    border: 1px solid var(--kb-border);
    border-radius: 7px;
  }

  .mobile-account div {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .mobile-account strong {
    overflow: hidden;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mobile-account span {
    margin-top: 2px;
    color: var(--kb-text-muted);
    font-size: 10px;
  }

  .mobile-account button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 5px 8px;
    color: #9b3d35;
    background: #fff;
    border: 1px solid #efd7d3;
    border-radius: 5px;
    font: inherit;
    font-size: 11px;
    cursor: pointer;
  }
}

@media (max-width: 560px) {
  .brand-subtitle,
  .account-copy {
    display: none;
  }

  .app-header {
    padding: 0 12px;
  }

  .account-avatar {
    width: 28px;
    height: 28px;
  }
}
</style>
