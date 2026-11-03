<script setup lang="ts">
import { ArrowUpRight, Database, Menu, X } from 'lucide-vue-next'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { navItems, site } from '../data/content'

const activeId = ref('overview')
const menuOpen = ref(false)
const scrolled = ref(false)
const progress = ref(0)
const source = site.links.source

const activeLabel = computed(
  () => navItems.find((item) => item.id === activeId.value)?.label || '项目概览',
)

function updateScrollState() {
  const scrollTop = window.scrollY
  const maxScroll = document.documentElement.scrollHeight - window.innerHeight
  scrolled.value = scrollTop > 16
  progress.value = maxScroll > 0 ? Math.min(100, (scrollTop / maxScroll) * 100) : 0

  let current = navItems[0]?.id || 'overview'
  for (const item of navItems) {
    const section = document.getElementById(item.id)
    if (section && section.getBoundingClientRect().top <= 150) {
      current = item.id
    }
  }
  activeId.value = current
}

function navigate(id: string) {
  menuOpen.value = false
  const section = document.getElementById(id)
  if (!section) return
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  section.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
}

function lockBody(open: boolean) {
  document.body.style.overflow = open ? 'hidden' : ''
}

watch(menuOpen, lockBody)

onMounted(() => {
  updateScrollState()
  window.addEventListener('scroll', updateScrollState, { passive: true })
  window.addEventListener('resize', updateScrollState)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', updateScrollState)
  window.removeEventListener('resize', updateScrollState)
  document.body.style.overflow = ''
})
</script>

<template>
  <header class="site-header" :class="{ 'is-scrolled': scrolled, 'is-open': menuOpen }">
    <div class="header-inner">
      <a class="brand" href="#overview" aria-label="知库 AI 项目概览" @click.prevent="navigate('overview')">
        <span class="brand-mark"><Database :size="17" stroke-width="1.8" /></span>
        <span class="brand-copy">
          <strong>知库 AI</strong>
          <small>KNOWBASE AI</small>
        </span>
      </a>

      <nav class="desktop-nav" aria-label="页面章节">
        <a
          v-for="item in navItems"
          :key="item.id"
          :href="`#${item.id}`"
          :class="{ active: activeId === item.id }"
          @click.prevent="navigate(item.id)"
        >
          {{ item.label }}
        </a>
      </nav>

      <div class="header-actions">
        <a
          v-if="source.state === 'live'"
          class="source-link"
          :href="source.url"
          target="_blank"
          rel="noreferrer"
        >
          源码仓库
          <ArrowUpRight :size="14" />
        </a>
        <span v-else class="source-pending">
          <span class="status-dot" />
          {{ source.note }}
        </span>
        <button class="menu-trigger" type="button" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">
          <X v-if="menuOpen" :size="20" />
          <Menu v-else :size="20" />
          <span class="sr-only">{{ menuOpen ? '关闭菜单' : '打开菜单' }}</span>
        </button>
      </div>
    </div>
    <div class="reading-progress" :style="{ width: `${progress}%` }" />

    <Transition name="menu">
      <div v-if="menuOpen" class="mobile-panel">
        <div class="mobile-panel-head">
          <span>当前章节</span>
          <strong>{{ activeLabel }}</strong>
        </div>
        <nav aria-label="移动端页面章节">
          <a
            v-for="(item, index) in navItems"
            :key="item.id"
            :href="`#${item.id}`"
            :class="{ active: activeId === item.id }"
            @click.prevent="navigate(item.id)"
          >
            <span>{{ String(index + 1).padStart(2, '0') }}</span>
            {{ item.label }}
          </a>
        </nav>
        <a
          v-if="source.state === 'live'"
          class="mobile-source"
          :href="source.url"
          target="_blank"
          rel="noreferrer"
        >
          查看源码仓库
          <ArrowUpRight :size="16" />
        </a>
        <p v-else class="mobile-source-pending">{{ source.note }}，配置后此处自动显示入口。</p>
      </div>
    </Transition>
  </header>
</template>

<style scoped>
.site-header {
  position: fixed;
  z-index: 100;
  top: 0;
  right: 0;
  left: 0;
  height: 72px;
  border-bottom: 1px solid transparent;
  transition:
    background-color 180ms ease,
    border-color 180ms ease,
    backdrop-filter 180ms ease;
}

.site-header.is-scrolled,
.site-header.is-open {
  background: rgba(6, 14, 24, 0.84);
  border-bottom-color: var(--line);
  backdrop-filter: blur(18px);
}

.header-inner {
  width: min(var(--max-width), calc(100% - 48px));
  height: 100%;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 24px;
  margin: 0 auto;
}

.brand {
  width: max-content;
  display: inline-flex;
  align-items: center;
  gap: 11px;
  color: var(--text-strong);
  text-decoration: none;
}

.brand-mark {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: #071018;
  background: var(--accent-cyan);
  border-radius: 7px;
  box-shadow: 0 0 32px rgba(72, 199, 217, 0.18);
}

.brand-copy {
  display: grid;
  line-height: 1;
}

.brand-copy strong {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.03em;
}

.brand-copy small {
  margin-top: 5px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.14em;
}

.desktop-nav {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.055);
  border-radius: 8px;
}

.desktop-nav a {
  position: relative;
  padding: 7px 12px;
  color: var(--text-muted);
  font-size: 12px;
  text-decoration: none;
  border-radius: 5px;
  transition:
    color 160ms ease,
    background-color 160ms ease;
}

.desktop-nav a:hover {
  color: var(--text-strong);
}

.desktop-nav a.active {
  color: var(--text-strong);
  background: rgba(255, 255, 255, 0.06);
}

.header-actions {
  justify-self: end;
  display: flex;
  align-items: center;
  gap: 12px;
}

.source-link,
.source-pending {
  height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  color: var(--text-secondary);
  font-size: 12px;
  text-decoration: none;
  border: 1px solid var(--line);
  border-radius: 6px;
}

.source-link:hover {
  color: var(--text-strong);
  border-color: var(--line-strong);
}

.source-pending {
  color: var(--text-muted);
}

.status-dot {
  width: 5px;
  height: 5px;
  background: var(--accent-amber);
  border-radius: 50%;
  box-shadow: 0 0 9px rgba(245, 184, 75, 0.5);
}

.menu-trigger {
  display: none;
  width: 38px;
  height: 38px;
  place-items: center;
  color: var(--text-strong);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--line);
  border-radius: 6px;
}

.reading-progress {
  position: absolute;
  bottom: -1px;
  left: 0;
  height: 1px;
  background: linear-gradient(90deg, var(--accent-blue), var(--accent-cyan));
  box-shadow: 0 0 12px rgba(72, 199, 217, 0.45);
}

.mobile-panel {
  position: fixed;
  top: 72px;
  right: 0;
  bottom: 0;
  left: 0;
  overflow-y: auto;
  padding: 28px 24px 44px;
  background: #071018;
}

.mobile-panel-head {
  display: flex;
  justify-content: space-between;
  padding-bottom: 18px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  border-bottom: 1px solid var(--line);
}

.mobile-panel-head strong {
  color: var(--accent-cyan);
  font-weight: 500;
}

.mobile-panel nav {
  display: grid;
  margin-top: 12px;
}

.mobile-panel nav a {
  display: flex;
  align-items: baseline;
  gap: 18px;
  padding: 18px 2px;
  color: var(--text-secondary);
  font-size: 24px;
  font-weight: 550;
  text-decoration: none;
  border-bottom: 1px solid var(--line);
}

.mobile-panel nav a span {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 10px;
}

.mobile-panel nav a.active {
  color: var(--text-strong);
}

.mobile-source {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24px;
  padding: 14px 16px;
  color: #061019;
  font-weight: 650;
  text-decoration: none;
  background: var(--accent-cyan);
  border-radius: 6px;
}

.mobile-source-pending {
  margin: 24px 0 0;
  color: var(--text-muted);
  font-size: 12px;
}

.menu-enter-active,
.menu-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.menu-enter-from,
.menu-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

@media (max-width: 1000px) {
  .header-inner {
    grid-template-columns: 1fr auto;
  }

  .desktop-nav {
    display: none;
  }

  .menu-trigger {
    display: grid;
  }
}

@media (max-width: 680px) {
  .header-inner {
    width: calc(100% - 28px);
  }

  .source-link,
  .source-pending {
    display: none;
  }
}
</style>
