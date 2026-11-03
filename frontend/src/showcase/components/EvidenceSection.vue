<script setup lang="ts">
import {
  Activity,
  CircleCheck,
  Clock3,
  FileText,
  Info,
  ShieldAlert,
  TestTube2,
  TriangleAlert,
} from 'lucide-vue-next'
import {
  evidenceMetrics,
  limitations,
  pendingChecks,
  ragMetrics,
} from '../data/content'
import SectionHeader from './SectionHeader.vue'
</script>

<template>
  <section id="evidence" class="section section-evidence section-anchor">
    <div class="section-inner">
      <SectionHeader
        eyebrow="05 / VERIFIED EVIDENCE"
        title="用测试说明能力，也说明边界"
        intro="以下结果来自当前仓库在 2026-10-31 可执行环境中的记录。待验证项与已验证项分开呈现。"
      />

      <div class="evidence-overview">
        <div class="metric-rail" data-reveal>
          <article v-for="metric in evidenceMetrics" :key="metric.label" class="metric-card">
            <div class="metric-top">
              <span>{{ metric.label }}</span>
              <CircleCheck v-if="metric.status === 'verified'" :size="14" />
            </div>
            <strong>{{ metric.value }}</strong>
            <p>{{ metric.detail }}</p>
          </article>
        </div>

        <div class="evidence-note" data-reveal>
          <Info :size="15" />
          <p>
            Java 测试中有 7 项环境依赖测试跳过，不能按 64 项全部通过解释。数字用于说明覆盖范围，不替代测试报告。
          </p>
        </div>
      </div>

      <div class="rag-panel" data-reveal>
        <div class="rag-heading">
          <div>
            <span class="panel-kicker">FIXED RAG EVALUATION</span>
            <h3>固定集评估结果</h3>
            <p>25 道人工核验题 · 10 份短文档 · 5 类问题</p>
          </div>
          <span class="measured-date"><Clock3 :size="12" /> 2026-10-31</span>
        </div>

        <div class="rag-body">
          <div class="rag-metrics">
            <div v-for="metric in ragMetrics" :key="metric.label">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
            </div>
          </div>

          <div class="rag-bars">
            <div class="bar-row">
              <div><span>Recall@5</span><strong>97.50%</strong></div>
              <div class="bar-track"><i style="width: 97.5%" /></div>
            </div>
            <div class="bar-row">
              <div><span>引用正确率</span><strong>100%</strong></div>
              <div class="bar-track citation"><i style="width: 100%" /></div>
            </div>
          </div>
        </div>

        <div class="rag-conditions">
          <span>Embedding: local-hash-v1</span>
          <span>Chat: deepseek-chat</span>
          <span>Top K: 5</span>
          <span>chunk: 600 / overlap 80</span>
        </div>
        <div class="rag-caveat">
          <TriangleAlert :size="14" />
          <p>
            这是演示级固定集评估。Recall 使用最终引用近似检索结果，样本小且 Embedding
            不具备真实语义泛化能力，不能表述为“系统准确率 97.5%”。
          </p>
        </div>
      </div>

      <div class="quality-grid">
        <div class="quality-column" data-reveal>
          <div class="column-heading">
            <TestTube2 :size="16" />
            <div>
              <span>VERIFIED</span>
              <h3>当前环境已执行</h3>
            </div>
          </div>
          <ul class="verified-list">
            <li>
              <CircleCheck :size="14" />
              <div><strong>权限矩阵</strong><span>47 个接口用例 × 3 角色，0 不匹配</span></div>
            </li>
            <li>
              <CircleCheck :size="14" />
              <div><strong>HTTP 闭环</strong><span>登录、建库、上传、索引、问答、引用 20/20</span></div>
            </li>
            <li>
              <CircleCheck :size="14" />
              <div><strong>生产编排配置</strong><span>Compose 解析通过，只发布 80/443</span></div>
            </li>
            <li>
              <CircleCheck :size="14" />
              <div><strong>文档链接</strong><span>本地 Markdown 链接与片段检查通过</span></div>
            </li>
          </ul>
        </div>

        <div class="quality-column pending" data-reveal>
          <div class="column-heading">
            <ShieldAlert :size="16" />
            <div>
              <span>PENDING</span>
              <h3>运行环境待验证</h3>
            </div>
          </div>
          <ul class="pending-list">
            <li v-for="check in pendingChecks" :key="check.title">
              <span class="pending-dot" />
              <div><strong>{{ check.title }}</strong><span>{{ check.detail }}</span></div>
            </li>
          </ul>
        </div>
      </div>

      <div class="limitations" data-reveal>
        <div class="limitations-title">
          <Info :size="16" />
          <span>已知限制</span>
        </div>
        <div class="limitation-list">
          <p v-for="item in limitations" :key="item">
            <span />
            {{ item }}
          </p>
        </div>
        <div class="report-links">
          <span><FileText :size="12" /> STAGE5_TEST_REPORT.md</span>
          <span><Activity :size="12" /> ACCEPTANCE.md</span>
          <span><TestTube2 :size="12" /> EVALUATION.md</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section-evidence {
  background:
    radial-gradient(circle at 18% 5%, rgba(70, 136, 224, 0.08), transparent 24%),
    #071018;
}

.metric-rail {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.metric-card {
  min-width: 0;
  padding: 23px 21px;
}

.metric-card + .metric-card {
  border-left: 1px solid var(--line);
}

.metric-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: #71849a;
  font-size: 9px;
}

.metric-top svg {
  color: var(--accent-green);
}

.metric-card strong {
  display: block;
  margin-top: 12px;
  color: var(--text-strong);
  font-family: var(--font-mono);
  font-size: clamp(2.3rem, 4vw, 3.8rem);
  font-weight: 500;
  letter-spacing: -0.05em;
  line-height: 1;
}

.metric-card p {
  margin: 10px 0 0;
  color: #7b8ca0;
  font-size: 9px;
  line-height: 1.6;
}

.evidence-note {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 10px;
  margin-top: 13px;
  padding: 11px 13px;
  color: var(--accent-amber);
  background: rgba(245, 184, 75, 0.035);
  border: 1px solid rgba(245, 184, 75, 0.11);
  border-radius: 5px;
}

.evidence-note p {
  margin: 0;
  color: #867b68;
  font-size: 9px;
  line-height: 1.7;
}

.rag-panel {
  margin-top: 48px;
  overflow: hidden;
  background:
    linear-gradient(120deg, rgba(72, 199, 217, 0.035), transparent 45%),
    #0a1521;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.rag-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 25px 28px 21px;
  border-bottom: 1px solid var(--line);
}

.panel-kicker {
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.1em;
}

.rag-heading h3 {
  margin: 8px 0 0;
  color: var(--text-strong);
  font-size: 23px;
  font-weight: 580;
  letter-spacing: -0.025em;
}

.rag-heading p {
  margin: 5px 0 0;
  color: #718398;
  font-size: 9px;
}

.measured-date {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 8px;
  color: #7f91a5;
  font-family: var(--font-mono);
  font-size: 8px;
  border: 1px solid var(--line);
  border-radius: 4px;
}

.rag-body {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 34px;
  padding: 26px 28px;
}

.rag-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}

.rag-metrics div {
  display: grid;
  gap: 7px;
}

.rag-metrics span {
  color: #718398;
  font-size: 9px;
}

.rag-metrics strong {
  color: #dce7f3;
  font-family: var(--font-mono);
  font-size: clamp(1.5rem, 2.6vw, 2.25rem);
  font-weight: 500;
  letter-spacing: -0.04em;
}

.rag-bars {
  display: grid;
  align-content: center;
  gap: 22px;
}

.bar-row {
  display: grid;
  gap: 8px;
}

.bar-row > div:first-child {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #74869a;
  font-size: 8px;
}

.bar-row strong {
  color: #b8c7d7;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 500;
}

.bar-track {
  height: 3px;
  overflow: hidden;
  background: #152538;
  border-radius: 2px;
}

.bar-track i {
  height: 100%;
  display: block;
  background: linear-gradient(90deg, #337ddd, #48c7d9);
}

.bar-track.citation i {
  background: linear-gradient(90deg, #35aa83, #63dab0);
}

.rag-conditions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  padding: 0 28px 20px;
}

.rag-conditions span {
  padding: 4px 7px;
  color: #63768b;
  font-family: var(--font-mono);
  font-size: 7px;
  border: 1px solid var(--line);
  border-radius: 3px;
}

.rag-caveat {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 9px;
  padding: 13px 28px;
  color: var(--accent-amber);
  background: rgba(245, 184, 75, 0.035);
  border-top: 1px solid rgba(245, 184, 75, 0.1);
}

.rag-caveat p {
  margin: 0;
  color: #837966;
  font-size: 9px;
  line-height: 1.65;
}

.quality-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin-top: 20px;
}

.quality-column {
  padding: 23px;
  background: #0a1521;
  border: 1px solid var(--line);
  border-radius: 7px;
}

.column-heading {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  color: var(--accent-green);
}

.column-heading > div {
  display: grid;
}

.column-heading span {
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.08em;
}

.column-heading h3 {
  margin: 3px 0 0;
  color: #cbd8e5;
  font-size: 14px;
  font-weight: 560;
}

.pending .column-heading {
  color: var(--accent-amber);
}

.verified-list,
.pending-list {
  display: grid;
  gap: 0;
  margin: 18px 0 0;
  padding: 0;
  list-style: none;
}

.verified-list li,
.pending-list li {
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  gap: 8px;
  padding: 12px 0;
  border-top: 1px solid var(--line);
}

.verified-list li > svg {
  margin-top: 1px;
  color: var(--accent-green);
}

.verified-list li > div,
.pending-list li > div {
  display: grid;
  gap: 3px;
}

.verified-list strong,
.pending-list strong {
  color: #aebdcd;
  font-size: 10px;
  font-weight: 550;
}

.verified-list span,
.pending-list span {
  color: #687a8f;
  font-size: 8px;
  line-height: 1.55;
}

.pending-dot {
  width: 6px;
  height: 6px;
  margin-top: 4px;
  background: var(--accent-amber);
  border-radius: 50%;
  box-shadow: 0 0 8px rgba(245, 184, 75, 0.28);
}

.limitations {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr) auto;
  gap: 28px;
  align-items: start;
  margin-top: 42px;
  padding: 24px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.limitations-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #8e9eb1;
  font-size: 10px;
}

.limitations-title svg {
  color: var(--accent-amber);
}

.limitation-list {
  display: grid;
  gap: 8px;
}

.limitation-list p {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr);
  gap: 7px;
  margin: 0;
  color: #6f8195;
  font-size: 8px;
  line-height: 1.65;
}

.limitation-list p span {
  width: 3px;
  height: 3px;
  margin-top: 5px;
  background: #485d73;
  border-radius: 50%;
}

.report-links {
  display: grid;
  gap: 6px;
}

.report-links span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #536b81;
  font-family: var(--font-mono);
  font-size: 6px;
}

@media (max-width: 960px) {
  .metric-rail {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric-card:nth-child(3) {
    border-left: 0;
    border-top: 1px solid var(--line);
  }

  .metric-card:nth-child(4) {
    border-top: 1px solid var(--line);
  }

  .rag-body {
    grid-template-columns: 1fr;
  }

  .quality-grid {
    grid-template-columns: 1fr;
  }

  .limitations {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .metric-card {
    padding: 18px 14px;
  }

  .rag-heading {
    flex-direction: column;
    padding: 21px 18px;
  }

  .rag-body {
    padding: 22px 18px;
  }

  .rag-conditions {
    padding: 0 18px 18px;
  }

  .rag-caveat {
    padding: 13px 18px;
  }

  .quality-column {
    padding: 18px;
  }
}
</style>
