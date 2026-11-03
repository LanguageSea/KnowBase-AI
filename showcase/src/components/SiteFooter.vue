<script setup lang="ts">
import {
  ArrowRight,
  ArrowUp,
  BookOpenText,
  Database,
  ExternalLink,
  FileText,
  Github,
  PlayCircle,
  ShieldCheck,
} from 'lucide-vue-next'
import { site } from '../data/content'

const links = site.links

function goTo(id: string) {
  const section = document.getElementById(id)
  if (!section) return
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  section.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
}

function goTop() {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' })
}

const linkCards = (
  [
    { key: 'demo', label: '在线体验', icon: PlayCircle },
    { key: 'source', label: '源码仓库', icon: Github },
    { key: 'readme', label: '项目 README', icon: BookOpenText },
    { key: 'runbook', label: '部署手册', icon: FileText },
  ] as const
).map((item) => {
  const state = links[item.key]
  return {
    ...item,
    url: state.state === 'live' ? state.url : '',
    note: state.state === 'pending' ? state.note : '打开外部入口',
  }
})
</script>

<template>
  <footer class="site-footer">
    <section class="footer-cta" data-reveal>
      <div class="footer-cta-grid" aria-hidden="true" />
      <div class="cta-copy">
        <span class="cta-kicker">KNOWLEDGE WITH BOUNDARIES</span>
        <h2>让每一条回答，<br />都回到可验证的依据。</h2>
        <p>
          从业务后台到 AI 服务，从权限过滤到固定集评估，完整工程链路已经留下可检查的证据。
        </p>
        <div class="cta-actions">
          <button type="button" class="cta-primary" @click="goTo('architecture')">
            再看系统架构
            <ArrowRight :size="15" />
          </button>
          <button type="button" class="cta-secondary" @click="goTo('evidence')">
            查看验证结果
            <ShieldCheck :size="14" />
          </button>
        </div>
      </div>

      <div class="link-board">
        <div class="link-board-head">
          <span>PROJECT LINKS</span>
          <small>空链接保持不可点击状态</small>
        </div>
        <template v-for="item in linkCards" :key="item.key">
          <a
            v-if="item.url"
            class="project-link is-live"
            :href="item.url"
            target="_blank"
            rel="noreferrer"
          >
            <span class="link-icon"><component :is="item.icon" :size="16" /></span>
            <span><strong>{{ item.label }}</strong><small>{{ item.note }}</small></span>
            <ExternalLink :size="14" />
          </a>
          <span v-else class="project-link is-pending">
            <span class="link-icon"><component :is="item.icon" :size="16" /></span>
            <span><strong>{{ item.label }}</strong><small>{{ item.note }}</small></span>
            <span class="pending-state">PENDING</span>
          </span>
        </template>
      </div>
    </section>

    <div class="footer-bottom">
      <div class="footer-brand">
        <span class="footer-mark"><Database :size="17" /></span>
        <span><strong>知库 AI</strong><small>KNOWBASE AI / PROJECT SHOWCASE</small></span>
      </div>
      <div class="footer-meta">
        <span>最后更新 {{ site.updatedAt }}</span>
        <span>Vue 3 · TypeScript · Vite</span>
        <span>纯静态展示，无业务 API 依赖</span>
      </div>
      <button type="button" class="back-top" @click="goTop">
        返回顶部
        <ArrowUp :size="13" />
      </button>
    </div>
  </footer>
</template>

<style scoped>
.site-footer {
  position: relative;
  padding: 0 24px 28px;
  color: var(--text-secondary);
  background: #050c14;
}

.footer-cta {
  position: relative;
  width: min(var(--max-width), 100%);
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: clamp(40px, 8vw, 120px);
  align-items: center;
  margin: 0 auto;
  overflow: hidden;
  padding: clamp(46px, 7vw, 86px);
  background:
    radial-gradient(circle at 18% 20%, rgba(47, 111, 237, 0.15), transparent 32%),
    #0a1521;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.footer-cta-grid {
  position: absolute;
  inset: 0;
  opacity: 0.28;
  background-image:
    linear-gradient(rgba(126, 155, 192, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(126, 155, 192, 0.08) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: linear-gradient(90deg, black, transparent 70%);
}

.cta-copy,
.link-board {
  position: relative;
  z-index: 1;
}

.cta-kicker {
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.1em;
}

.cta-copy h2 {
  margin: 17px 0 0;
  color: var(--text-strong);
  font-size: clamp(2.25rem, 5vw, 4.4rem);
  font-weight: 590;
  letter-spacing: -0.055em;
  line-height: 1.04;
}

.cta-copy p {
  max-width: 590px;
  margin: 21px 0 0;
  color: #8192a7;
  font-size: 11px;
  line-height: 1.8;
}

.cta-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin-top: 29px;
}

.cta-actions button {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 15px;
  font-size: 11px;
  font-weight: 600;
  border-radius: 5px;
  cursor: pointer;
}

.cta-primary {
  color: #071018;
  background: var(--text-strong);
  border: 1px solid var(--text-strong);
}

.cta-secondary {
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--line-strong);
}

.link-board {
  overflow: hidden;
  background: rgba(4, 11, 18, 0.55);
  border: 1px solid var(--line);
  border-radius: 6px;
  backdrop-filter: blur(12px);
}

.link-board-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}

.link-board-head span {
  color: #8193a8;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.08em;
}

.link-board-head small {
  color: #52657a;
  font-size: 8px;
}

.project-link {
  min-height: 63px;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px 15px;
  text-decoration: none;
}

.project-link + .project-link {
  border-top: 1px solid var(--line);
}

.is-live:hover {
  background: rgba(255, 255, 255, 0.025);
}

.link-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  color: var(--accent-cyan);
  background: rgba(72, 199, 217, 0.055);
  border-radius: 4px;
}

.project-link > span:nth-child(2) {
  display: grid;
}

.project-link strong {
  color: #b9c6d4;
  font-size: 10px;
  font-weight: 550;
}

.project-link small {
  color: #607286;
  font-size: 8px;
}

.project-link > svg {
  color: #60768c;
}

.is-pending {
  opacity: 0.66;
}

.pending-state {
  color: #826d44;
  font-family: var(--font-mono);
  font-size: 6px;
  letter-spacing: 0.05em;
}

.footer-bottom {
  width: min(var(--max-width), 100%);
  display: grid;
  grid-template-columns: 1fr auto auto;
  align-items: center;
  gap: 30px;
  margin: 0 auto;
  padding: 26px 2px 0;
}

.footer-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}

.footer-mark {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  color: #071018;
  background: var(--accent-cyan);
  border-radius: 5px;
}

.footer-brand > span:last-child {
  display: grid;
}

.footer-brand strong {
  color: #c0cdda;
  font-size: 11px;
  font-weight: 600;
}

.footer-brand small {
  color: #506278;
  font-family: var(--font-mono);
  font-size: 6px;
  letter-spacing: 0.08em;
}

.footer-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 16px;
  color: #52657a;
  font-family: var(--font-mono);
  font-size: 7px;
}

.back-top {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 9px;
  color: #687c92;
  font-size: 8px;
  background: transparent;
  border: 1px solid var(--line);
  border-radius: 4px;
  cursor: pointer;
}

.back-top:hover {
  color: var(--text-strong);
}

@media (max-width: 980px) {
  .footer-cta {
    grid-template-columns: 1fr;
  }

  .footer-bottom {
    grid-template-columns: 1fr auto;
  }

  .footer-meta {
    grid-column: 1 / -1;
    grid-row: 2;
    justify-content: flex-start;
  }
}

@media (max-width: 640px) {
  .site-footer {
    padding-inline: 14px;
  }

  .footer-cta {
    padding: 30px 20px;
  }

  .footer-bottom {
    grid-template-columns: 1fr;
    gap: 18px;
  }

  .footer-meta {
    grid-column: auto;
    grid-row: auto;
    flex-direction: column;
    gap: 6px;
  }

  .back-top {
    width: max-content;
  }
}
</style>
