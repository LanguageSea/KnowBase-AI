<script setup lang="ts">
import {
  ArrowDown,
  ArrowRight,
  ArrowUpRight,
  CirclePlay,
  Database,
  GitBranch,
  LockKeyhole,
  ShieldCheck,
  TerminalSquare,
} from 'lucide-vue-next'
import { getToken } from '../../utils/auth'
import ProductFrame from './ProductFrame.vue'
import { heroFacts, site } from '../data/content'

const sourceLink = site.links.source
const entryPath = getToken() ? '/knowledge-bases' : '/login'

function scrollTo(id: string) {
  const section = document.getElementById(id)
  if (!section) return
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  section.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
}
</script>

<template>
  <section id="overview" class="hero section-anchor">
    <div class="hero-grid" aria-hidden="true" />
    <div class="hero-orbit orbit-one" aria-hidden="true" />
    <div class="hero-orbit orbit-two" aria-hidden="true" />

    <div class="hero-content">
      <div class="hero-copy" data-reveal>
        <div class="hero-kicker">
          <span class="kicker-pulse" />
          FULL-STACK RAG SYSTEM
          <span class="kicker-divider" />
          <span>V1.0 / STAGE 5</span>
        </div>

        <h1>
          <span>知库 AI</span>
          <small>KnowBase AI</small>
        </h1>

        <div class="hero-lead-row">
          <p class="hero-lead">{{ site.tagline }}</p>
          <span class="hero-scope">
            <LockKeyhole :size="13" />
            权限先行 · 引用可追溯
          </span>
        </div>

        <p class="hero-description">{{ site.description }}</p>

        <div class="hero-actions">
          <button class="action-primary" type="button" @click="scrollTo('architecture')">
            查看系统架构
            <ArrowRight :size="16" />
          </button>
          <RouterLink class="action-secondary" :to="entryPath">
            <CirclePlay :size="15" />
            进入系统
            <ArrowRight :size="14" />
          </RouterLink>
          <a
            v-if="sourceLink.state === 'live'"
            class="action-text"
            :href="sourceLink.url"
            target="_blank"
            rel="noreferrer"
          >
            查看源码
            <ArrowUpRight :size="14" />
          </a>
          <span v-else class="action-text is-pending">{{ sourceLink.note }}</span>
        </div>

        <div class="hero-stack" aria-label="核心技术栈">
          <span>Vue 3</span>
          <span>Spring Boot</span>
          <span>FastAPI</span>
          <span>MySQL</span>
          <span>Qdrant</span>
        </div>
      </div>

      <div class="hero-product" data-reveal>
        <div class="product-note note-left">
          <span class="note-icon"><ShieldCheck :size="15" /></span>
          <span><small>RETRIEVAL SCOPE</small>服务端授权过滤</span>
        </div>
        <div class="product-note note-right">
          <span class="note-icon cyan"><GitBranch :size="15" /></span>
          <span><small>ASYNC PIPELINE</small>任务可重试、可回收</span>
        </div>
        <div class="product-wrapper">
          <ProductFrame variant="qa" />
        </div>
      </div>

      <div class="hero-proof" data-reveal>
        <div class="proof-intro">
          <TerminalSquare :size="16" />
          <span>当前实现证据</span>
          <small>基于仓库真实测试与验收记录</small>
        </div>
        <div v-for="fact in heroFacts" :key="fact.label" class="proof-item">
          <strong>{{ fact.value }}</strong>
          <span>{{ fact.label }}</span>
        </div>
        <button type="button" class="proof-more" aria-label="前往测试与验证" @click="scrollTo('evidence')">
          <ArrowDown :size="16" />
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.hero {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  padding: 140px 0 54px;
  background:
    radial-gradient(circle at 72% 12%, rgba(45, 117, 220, 0.16), transparent 29%),
    radial-gradient(circle at 14% 65%, rgba(72, 199, 217, 0.075), transparent 22%),
    #071018;
}

.hero::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 180px;
  background: linear-gradient(180deg, transparent, #071018);
  content: '';
  pointer-events: none;
}

.hero-grid {
  position: absolute;
  inset: 0;
  opacity: 0.3;
  background-image:
    linear-gradient(rgba(124, 159, 205, 0.09) 1px, transparent 1px),
    linear-gradient(90deg, rgba(124, 159, 205, 0.09) 1px, transparent 1px);
  background-size: 64px 64px;
  mask-image: linear-gradient(to bottom, black 0%, rgba(0, 0, 0, 0.68) 55%, transparent 100%);
}

.hero-orbit {
  position: absolute;
  border: 1px solid rgba(87, 142, 211, 0.1);
  border-radius: 50%;
  pointer-events: none;
}

.orbit-one {
  top: 15%;
  left: 50%;
  width: 820px;
  height: 820px;
  transform: translateX(-18%);
}

.orbit-two {
  top: 23%;
  left: 50%;
  width: 1120px;
  height: 1120px;
  transform: translateX(-23%);
  opacity: 0.5;
}

.hero-content {
  position: relative;
  z-index: 1;
  width: min(var(--max-width), calc(100% - 48px));
  margin: 0 auto;
}

.hero-copy {
  max-width: 1040px;
}

.hero-kicker {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #8393a8;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
}

.kicker-pulse {
  width: 6px;
  height: 6px;
  background: var(--accent-cyan);
  border-radius: 50%;
  box-shadow: 0 0 0 5px rgba(72, 199, 217, 0.08);
}

.kicker-divider {
  width: 28px;
  height: 1px;
  margin: 0 2px;
  background: var(--line-strong);
}

h1 {
  display: flex;
  align-items: baseline;
  gap: 24px;
  margin: 23px 0 0;
  color: var(--text-strong);
  font-size: clamp(4.8rem, 10vw, 9.5rem);
  font-weight: 610;
  letter-spacing: -0.07em;
  line-height: 0.88;
}

h1 > span {
  white-space: nowrap;
}

h1 small {
  color: #74869d;
  font-family: var(--font-mono);
  font-size: clamp(0.7rem, 1.4vw, 1rem);
  font-weight: 400;
  letter-spacing: 0.04em;
}

.hero-lead-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 31px;
}

.hero-lead {
  margin: 0;
  color: #d8e3f0;
  font-size: clamp(1.2rem, 2vw, 1.65rem);
  font-weight: 450;
}

.hero-scope {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 9px;
  color: #75ddc0;
  font-family: var(--font-mono);
  font-size: 9px;
  background: rgba(74, 198, 154, 0.07);
  border: 1px solid rgba(82, 211, 162, 0.18);
  border-radius: 4px;
}

.hero-description {
  max-width: 810px;
  margin: 20px 0 0;
  color: var(--text-secondary);
  font-size: clamp(0.96rem, 1.4vw, 1.08rem);
  line-height: 1.82;
}

.hero-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 29px;
}

.action-primary,
.action-secondary,
.action-text {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 0 16px;
  font-size: 12px;
  font-weight: 600;
  text-decoration: none;
  border-radius: 5px;
}

.action-primary {
  color: #061019;
  background: var(--text-strong);
  border: 1px solid var(--text-strong);
  cursor: pointer;
  transition:
    transform 160ms ease,
    background-color 160ms ease;
}

.action-primary:hover {
  background: #fff;
  transform: translateY(-1px);
}

.action-secondary {
  color: var(--text-strong);
  background: rgba(255, 255, 255, 0.025);
  border: 1px solid var(--line-strong);
}

.action-secondary.is-pending {
  color: var(--text-muted);
}

.action-text {
  min-height: 40px;
  padding-inline: 9px;
  color: var(--text-secondary);
}

.action-text:hover {
  color: var(--text-strong);
}

.action-text.is-pending {
  color: #6f7c8d;
}

.hero-stack {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 23px;
}

.hero-stack span {
  padding: 4px 7px;
  color: #75869b;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.04em;
  border: 1px solid rgba(118, 143, 176, 0.12);
  border-radius: 3px;
}

.hero-product {
  position: relative;
  width: min(1220px, 100%);
  margin: 54px auto 0;
}

.product-wrapper {
  position: relative;
  z-index: 2;
  transform: perspective(1600px) rotateX(1.1deg);
  transform-origin: 50% 100%;
}

.product-note {
  position: absolute;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 13px;
  color: #c7d4e3;
  font-size: 10px;
  background: rgba(9, 19, 31, 0.92);
  border: 1px solid rgba(122, 156, 197, 0.19);
  border-radius: 6px;
  box-shadow: 0 14px 36px rgba(0, 0, 0, 0.24);
  backdrop-filter: blur(12px);
}

.product-note small {
  display: block;
  color: #718199;
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.08em;
}

.note-left {
  top: 25%;
  left: -45px;
}

.note-right {
  right: -35px;
  bottom: 22%;
}

.note-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  color: var(--accent-green);
  background: rgba(82, 211, 162, 0.08);
  border-radius: 5px;
}

.note-icon.cyan {
  color: var(--accent-cyan);
  background: rgba(72, 199, 217, 0.08);
}

.hero-proof {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: 1.7fr repeat(4, 1fr) auto;
  align-items: stretch;
  margin-top: 28px;
  background: rgba(11, 25, 40, 0.8);
  border: 1px solid rgba(119, 151, 190, 0.14);
  border-radius: 7px;
  backdrop-filter: blur(16px);
}

.proof-intro,
.proof-item,
.proof-more {
  min-height: 77px;
}

.proof-intro {
  display: grid;
  grid-template-columns: auto 1fr;
  align-content: center;
  gap: 3px 11px;
  padding: 14px 18px;
  color: var(--accent-cyan);
}

.proof-intro > span {
  color: var(--text-strong);
  font-size: 12px;
  font-weight: 600;
}

.proof-intro small {
  grid-column: 2;
  color: var(--text-muted);
  font-size: 9px;
}

.proof-item {
  display: grid;
  align-content: center;
  padding: 13px 16px;
  border-left: 1px solid var(--line);
}

.proof-item strong {
  color: var(--text-strong);
  font-family: var(--font-mono);
  font-size: 24px;
  font-weight: 520;
  letter-spacing: -0.04em;
}

.proof-item span {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 9px;
}

.proof-more {
  width: 48px;
  display: grid;
  place-items: center;
  color: var(--text-muted);
  background: transparent;
  border: 0;
  border-left: 1px solid var(--line);
  cursor: pointer;
}

.proof-more:hover {
  color: var(--accent-cyan);
}

@media (max-width: 1100px) {
  .note-left {
    left: -10px;
  }

  .note-right {
    right: -10px;
  }

  .hero-proof {
    grid-template-columns: repeat(4, 1fr) auto;
  }

  .proof-intro {
    grid-column: 1 / -1;
    min-height: 58px;
    border-bottom: 1px solid var(--line);
  }
}

@media (max-width: 760px) {
  .hero {
    padding-top: 112px;
  }

  .hero-content {
    width: calc(100% - 28px);
  }

  .hero-kicker {
    flex-wrap: wrap;
    font-size: 8px;
  }

  h1 {
    display: grid;
    gap: 14px;
    margin-top: 22px;
    font-size: clamp(4.2rem, 22vw, 6.8rem);
    line-height: 0.9;
  }

  h1 small {
    font-size: 10px;
  }

  .hero-lead-row {
    margin-top: 24px;
  }

  .hero-product {
    margin-top: 38px;
  }

  .product-wrapper {
    transform: none;
  }

  .product-note {
    display: none;
  }

  .hero-proof {
    grid-template-columns: repeat(2, 1fr);
  }

  .proof-intro {
    min-height: 66px;
  }

  .proof-item {
    border-top: 1px solid var(--line);
  }

  .proof-item:nth-of-type(3),
  .proof-item:nth-of-type(5) {
    border-left: 0;
  }

  .proof-more {
    display: none;
  }
}

@media (max-width: 430px) {
  .hero-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .action-primary,
  .action-secondary,
  .action-text {
    width: 100%;
  }

  .action-text {
    min-height: 28px;
  }
}
</style>
