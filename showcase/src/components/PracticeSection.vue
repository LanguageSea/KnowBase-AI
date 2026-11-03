<script setup lang="ts">
import { ArrowRight, Compass, GitBranch, ShieldCheck, Workflow } from 'lucide-vue-next'
import { practiceHighlights } from '../data/content'
import SectionHeader from './SectionHeader.vue'

const decisions = [
  {
    question: '为什么保留模块化单体？',
    choice: '业务事务、权限和审计仍在一个 Java 服务内完成，AI 计算独立为 Python 服务。',
    cost: '少一次业务拆分的运维成本，多一次内部网络调用和链路追踪要求。',
  },
  {
    question: '为什么不用消息队列？',
    choice: 'V1 使用 MySQL 任务表、条件更新和定时回收完成单机异步调度。',
    cost: '部署更简单，但吞吐与调度能力有限，扩容时需要重新评估队列方案。',
  },
  {
    question: '为什么保留失败指标？',
    choice: '展示固定集结果、测量条件和待验证项，不把演示数据包装成生产 SLA。',
    cost: '页面少一些“漂亮数字”，但面试追问时每个结论都有出处。',
  },
]
</script>

<template>
  <section id="practice" class="section section-practice section-anchor">
    <div class="section-inner">
      <SectionHeader
        eyebrow="06 / ENGINEERING PRACTICE"
        title="工程判断，比功能数量更重要"
        intro="项目按纵向切片推进：先跑通主链路，再补权限、可靠性、测试与交付。每一步都留下可验收证据。"
      />

      <div class="practice-grid">
        <div class="practice-story" data-reveal>
          <div class="story-mark">
            <Compass :size="21" />
          </div>
          <p>
            这个项目的技术重点不是“接入了模型”，而是让一次知识问答同时满足
            <strong>业务事务</strong>、<strong>资源权限</strong>、<strong>异步状态</strong>和
            <strong>引用可信</strong>。
          </p>
          <div class="story-path">
            <span>VERTICAL SLICE</span>
            <ArrowRight :size="13" />
            <span>PERMISSION</span>
            <ArrowRight :size="13" />
            <span>OBSERVABILITY</span>
            <ArrowRight :size="13" />
            <span>EVIDENCE</span>
          </div>
        </div>

        <div class="practice-list">
          <article
            v-for="item in practiceHighlights"
            :key="item.index"
            class="practice-item"
            data-reveal
          >
            <span class="practice-index">{{ item.index }}</span>
            <div class="practice-copy">
              <h3>{{ item.title }}</h3>
              <p>{{ item.body }}</p>
            </div>
            <div class="practice-proof">
              <span><GitBranch :size="12" /> EVIDENCE</span>
              <p>{{ item.proof }}</p>
            </div>
          </article>
        </div>
      </div>

      <div class="decision-heading" data-reveal>
        <div>
          <Workflow :size="17" />
          <span>关键取舍</span>
        </div>
        <p>面试中可继续展开的设计选择</p>
      </div>

      <div class="decision-grid">
        <article v-for="(decision, index) in decisions" :key="decision.question" data-reveal>
          <span class="decision-number">0{{ index + 1 }}</span>
          <h3>{{ decision.question }}</h3>
          <div class="decision-row">
            <span>选择</span>
            <p>{{ decision.choice }}</p>
          </div>
          <div class="decision-row cost">
            <span>代价</span>
            <p>{{ decision.cost }}</p>
          </div>
        </article>
      </div>

      <div class="practice-ending" data-reveal>
        <ShieldCheck :size="17" />
        <p>
          可讲清楚的边界包括：<strong>local-hash-v1 Embedding</strong>、无 OCR、
          单机 Compose、无高可用与并发容量基线。已知问题不会被包装成“未来规划”掩盖。
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section-practice {
  background:
    linear-gradient(180deg, #071018, #0d1824 44%, #071018);
  border-top: 1px solid var(--line);
}

.practice-grid {
  display: grid;
  grid-template-columns: 0.8fr 1.2fr;
  gap: clamp(42px, 7vw, 92px);
  align-items: start;
}

.practice-story {
  position: sticky;
  top: 118px;
}

.story-mark {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  color: var(--accent-cyan);
  background: rgba(72, 199, 217, 0.055);
  border: 1px solid rgba(72, 199, 217, 0.12);
  border-radius: 6px;
}

.practice-story > p {
  max-width: 470px;
  margin: 23px 0 0;
  color: var(--text-secondary);
  font-size: clamp(1.25rem, 2vw, 1.65rem);
  line-height: 1.65;
}

.practice-story strong {
  color: var(--text-strong);
  font-weight: 580;
}

.story-path {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 27px;
  color: #65788f;
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.05em;
}

.story-path svg {
  color: #385169;
}

.practice-list {
  border-top: 1px solid var(--line);
}

.practice-item {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) minmax(180px, 0.72fr);
  gap: 22px;
  padding: 27px 0;
  border-bottom: 1px solid var(--line);
}

.practice-index {
  color: #53677d;
  font-family: var(--font-mono);
  font-size: 8px;
}

.practice-copy h3 {
  margin: 0;
  color: var(--text-strong);
  font-size: 19px;
  font-weight: 560;
  letter-spacing: -0.025em;
}

.practice-copy p {
  margin: 8px 0 0;
  color: #75879b;
  font-size: 10px;
  line-height: 1.75;
}

.practice-proof {
  padding-left: 18px;
  border-left: 1px solid var(--line);
}

.practice-proof span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--accent-green);
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.06em;
}

.practice-proof p {
  margin: 8px 0 0;
  color: #63768b;
  font-size: 9px;
  line-height: 1.65;
}

.decision-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-top: 58px;
  padding: 14px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.decision-heading > div {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: #b9c7d5;
  font-size: 11px;
}

.decision-heading svg {
  color: var(--accent-blue);
}

.decision-heading > p {
  margin: 0;
  color: #5d7086;
  font-family: var(--font-mono);
  font-size: 7px;
}

.decision-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-bottom: 1px solid var(--line);
}

.decision-grid article {
  min-width: 0;
  padding: 25px 24px 27px;
}

.decision-grid article + article {
  border-left: 1px solid var(--line);
}

.decision-number {
  color: #4f6479;
  font-family: var(--font-mono);
  font-size: 8px;
}

.decision-grid h3 {
  min-height: 48px;
  margin: 10px 0 20px;
  color: #c4d1df;
  font-size: 16px;
  font-weight: 560;
  line-height: 1.45;
}

.decision-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 9px;
  padding: 12px 0;
  border-top: 1px solid var(--line);
}

.decision-row span {
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 7px;
}

.decision-row.cost span {
  color: var(--accent-amber);
}

.decision-row p {
  margin: 0;
  color: #6d8095;
  font-size: 9px;
  line-height: 1.68;
}

.practice-ending {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 11px;
  margin-top: 20px;
  padding: 14px 16px;
  color: var(--accent-amber);
  background: rgba(245, 184, 75, 0.035);
  border: 1px solid rgba(245, 184, 75, 0.1);
  border-radius: 5px;
}

.practice-ending p {
  margin: 0;
  color: #817868;
  font-size: 9px;
  line-height: 1.7;
}

.practice-ending strong {
  color: #a59a86;
  font-weight: 550;
}

@media (max-width: 1000px) {
  .practice-grid {
    grid-template-columns: 1fr;
  }

  .practice-story {
    position: relative;
    top: 0;
  }

  .decision-grid {
    grid-template-columns: 1fr;
  }

  .decision-grid article + article {
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .decision-grid h3 {
    min-height: 0;
  }
}

@media (max-width: 700px) {
  .practice-item {
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 14px;
  }

  .practice-proof {
    grid-column: 2;
    padding: 13px 0 0;
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .decision-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
