<script setup lang="ts">
import { ArrowRight, FileSearch, KeyRound, ShieldCheck } from 'lucide-vue-next'
import SectionHeader from './SectionHeader.vue'
import { problems } from '../data/content'

const problemIcons = [FileSearch, ShieldCheck, KeyRound]
</script>

<template>
  <section id="context" class="section section-context">
    <div class="section-inner">
      <SectionHeader
        eyebrow="01 / PROJECT CONTEXT"
        title="知识越多，回答越需要边界"
        intro="知库 AI 把企业文档转成可检索的知识，但权限、依据和可恢复性始终优先于生成效果。"
      />

      <div class="context-layout">
        <div class="context-statement" data-reveal>
          <span class="large-index">01</span>
          <p>
            这不是一个只包一层聊天框的 RAG 页面。系统的核心在生成之前：
            <strong>谁可以检索什么</strong>，以及生成之后：<strong>答案凭什么被信任</strong>。
          </p>
          <div class="statement-rule">
            <span>JAVA AUTHORIZES</span>
            <ArrowRight :size="14" />
            <span>PYTHON COMPUTES</span>
          </div>
        </div>

        <div class="problem-list">
          <article
            v-for="(problem, index) in problems"
            :key="problem.number"
            class="problem-row"
            data-reveal
          >
            <div class="problem-icon">
              <component :is="problemIcons[index]" :size="19" :stroke-width="1.7" />
            </div>
            <div class="problem-main">
              <span>{{ problem.number }}</span>
              <h3>{{ problem.title }}</h3>
              <p>{{ problem.problem }}</p>
            </div>
            <div class="problem-solution">
              <span>实现策略</span>
              <p>{{ problem.solution }}</p>
            </div>
          </article>
        </div>
      </div>

      <div class="context-footer" data-reveal>
        <span class="footer-label">SYSTEM PRINCIPLE</span>
        <p>先把知识关进授权范围，再让模型回答问题。</p>
        <div class="principle-tags">
          <span><ShieldCheck :size="13" /> 检索前鉴权</span>
          <span><FileSearch :size="13" /> 引用可回验</span>
          <span><KeyRound :size="13" /> 密钥不回显</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section-context {
  position: relative;
  z-index: 2;
  background: #071018;
}

.context-layout {
  display: grid;
  grid-template-columns: 0.83fr 1.17fr;
  gap: clamp(48px, 7vw, 100px);
  align-items: start;
}

.context-statement {
  position: sticky;
  top: 118px;
  padding-top: 10px;
}

.large-index {
  color: rgba(72, 199, 217, 0.18);
  font-family: var(--font-mono);
  font-size: 108px;
  font-weight: 500;
  letter-spacing: -0.09em;
  line-height: 0.8;
}

.context-statement p {
  max-width: 450px;
  margin: 27px 0 0;
  color: var(--text-secondary);
  font-size: clamp(1.15rem, 1.8vw, 1.45rem);
  line-height: 1.72;
}

.context-statement strong {
  color: var(--text-strong);
  font-weight: 600;
}

.statement-rule {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 30px;
  color: #718196;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.09em;
}

.statement-rule svg {
  color: var(--accent-cyan);
}

.problem-list {
  border-top: 1px solid var(--line);
}

.problem-row {
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr) minmax(180px, 0.72fr);
  gap: 24px;
  align-items: start;
  padding: 28px 0;
  border-bottom: 1px solid var(--line);
}

.problem-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  color: var(--accent-cyan);
  background: rgba(72, 199, 217, 0.06);
  border: 1px solid rgba(72, 199, 217, 0.12);
  border-radius: 6px;
}

.problem-main > span,
.problem-solution > span {
  color: #5d6e83;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.08em;
}

.problem-main h3 {
  margin: 8px 0 7px;
  color: var(--text-strong);
  font-size: clamp(1.1rem, 1.8vw, 1.35rem);
  font-weight: 580;
  letter-spacing: -0.02em;
}

.problem-main p,
.problem-solution p {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.72;
}

.problem-solution {
  padding-left: 20px;
  border-left: 1px solid var(--line);
}

.problem-solution p {
  margin-top: 9px;
  color: #abb8c8;
}

.context-footer {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 24px;
  margin-top: 54px;
  padding: 20px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.footer-label {
  color: #5e7087;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.08em;
}

.context-footer > p {
  margin: 0;
  color: #c6d2df;
  font-size: 14px;
}

.principle-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.principle-tags span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 7px;
  color: var(--text-muted);
  font-size: 9px;
  border: 1px solid var(--line);
  border-radius: 3px;
}

@media (max-width: 980px) {
  .context-layout {
    grid-template-columns: 1fr;
  }

  .context-statement {
    position: relative;
    top: 0;
  }
}

@media (max-width: 720px) {
  .problem-row {
    grid-template-columns: 38px minmax(0, 1fr);
    gap: 14px;
  }

  .problem-icon {
    width: 36px;
    height: 36px;
  }

  .problem-solution {
    grid-column: 2;
    padding: 14px 0 0;
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .context-footer {
    grid-template-columns: 1fr;
    gap: 12px;
  }
}
</style>
