<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { BookOpenText, Database, Files, History, LibraryBig, MessageSquareText, Sparkles } from 'lucide-vue-next'
import { workspaceApi } from '../api'
import { notifyError } from '../utils/notify'
import MainLayout from '../layouts/MainLayout.vue'
import StatCard from '../components/common/StatCard.vue'
import StatusTag from '../components/common/StatusTag.vue'
import EmptyState from '../components/common/EmptyState.vue'

const router = useRouter()
const loading = ref(false)
const summary = ref(null)

const maxDaily = computed(() =>
  Math.max(1, ...(summary.value?.dailyQuestions || []).map((item) => Number(item.count || 0))),
)

function formatDate(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    summary.value = await workspaceApi.summary()
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <MainLayout eyebrow="个人工作台" title="工作台" subtitle="数据只统计当前账号可访问的知识库与个人会话。">
    <template #actions>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" @click="router.push({ name: 'workspace' })">开始提问</el-button>
    </template>

    <section v-if="loading && !summary" class="stat-grid" aria-label="加载中">
      <div v-for="n in 5" :key="n" class="kb-card stat-loading"><div class="kb-skeleton" /><div class="kb-skeleton short" /></div>
    </section>
    <section v-else class="stat-grid" aria-label="工作台统计">
      <StatCard label="可访问知识库" :value="summary?.accessibleKbCount ?? 0" hint="当前账号可见">
        <template #icon><LibraryBig :size="18" aria-hidden="true" /></template>
      </StatCard>
      <StatCard label="可用文档" :value="summary?.activeDocumentCount ?? 0" hint="已入库可检索">
        <template #icon><Files :size="18" aria-hidden="true" /></template>
      </StatCard>
      <StatCard label="我的会话" :value="summary?.conversationCount ?? 0" hint="累计创建">
        <template #icon><History :size="18" aria-hidden="true" /></template>
      </StatCard>
      <StatCard label="累计提问" :value="summary?.questionCount ?? 0" hint="USER 消息总数">
        <template #icon><MessageSquareText :size="18" aria-hidden="true" /></template>
      </StatCard>
      <StatCard label="待处理 / 异常文档" :value="summary?.processingDocumentCount ?? 0" tone="warning" hint="排队 / 处理中 / 失败">
        <template #icon><Database :size="18" aria-hidden="true" /></template>
      </StatCard>
    </section>

    <div class="dashboard-grid">
      <section class="kb-card chart-panel">
        <div class="panel-head">
          <div>
            <h2>近 7 天提问</h2>
            <p>按当前账号的 USER 消息实时聚合</p>
          </div>
          <el-button text @click="router.push({ name: 'workspace' })">
            <span class="link-with-icon"><Sparkles :size="14" aria-hidden="true" />去提问</span>
          </el-button>
        </div>
        <div v-if="loading && !summary" class="chart-loading">
          <div v-for="n in 7" :key="n" class="kb-skeleton bar-skeleton" />
        </div>
        <div v-else class="bar-chart" aria-label="近七天提问数量">
          <div v-for="item in summary?.dailyQuestions || []" :key="item.date" class="bar-item">
            <span class="bar-value">{{ item.count }}</span>
            <div class="bar-track">
              <div
                class="bar-fill"
                :style="{ height: `${Math.max(6, (Number(item.count) / maxDaily) * 100)}%` }"
              />
            </div>
            <span class="bar-label">{{ String(item.date).slice(5) }}</span>
          </div>
        </div>
      </section>

      <section class="kb-card">
        <div class="panel-head">
          <div>
            <h2>最近文档</h2>
            <p>仅包含当前账号有权访问的知识库</p>
          </div>
          <el-button text @click="router.push({ name: 'knowledge-bases' })">全部知识库</el-button>
        </div>
        <div v-if="summary?.latestDocuments?.length" class="document-list">
          <button
            v-for="doc in summary.latestDocuments"
            :key="doc.id"
            type="button"
            class="document-row"
            @click="router.push({ name: 'document-detail', params: { kbId: doc.kbId, docId: doc.id } })"
          >
            <span class="document-main">
              <strong>{{ doc.name }}</strong>
              <small>{{ doc.kbName }} · {{ formatDate(doc.createdAt) }}</small>
            </span>
            <StatusTag :status="doc.status" />
          </button>
        </div>
        <EmptyState
          v-else
          title="暂无可访问文档"
          description="上传文档并完成解析后，这里会显示最近更新。"
          action-label="去知识库看看"
          @action="router.push({ name: 'knowledge-bases' })"
        >
          <template #icon><BookOpenText :size="20" aria-hidden="true" /></template>
        </EmptyState>
      </section>
    </div>
  </MainLayout>
</template>

<style scoped>
.stat-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.stat-loading { padding: 16px; display: grid; gap: 10px; }
.kb-skeleton.short { width: 56%; }
.dashboard-grid { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(360px, 0.85fr); gap: 12px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; min-height: 62px; padding: 12px 18px; border-bottom: 1px solid var(--kb-border); }
.panel-head h2 { font-size: 14px; font-weight: 650; }
.panel-head p { margin-top: 2px; color: var(--kb-text-muted); font-size: 11px; }
.link-with-icon { display: inline-flex; align-items: center; gap: 5px; }
.chart-loading { display: grid; grid-template-columns: repeat(7, 1fr); gap: 14px; align-items: end; height: 230px; padding: 20px 22px 16px; }
.bar-skeleton { height: 100%; }
.bar-chart { height: 230px; display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); align-items: end; gap: 14px; padding: 20px 22px 16px; }
.bar-item { height: 100%; display: grid; grid-template-rows: 18px 1fr 20px; align-items: end; text-align: center; }
.bar-value, .bar-label { color: var(--kb-text-muted); font-size: 11px; }
.bar-track { height: 100%; display: flex; align-items: flex-end; justify-content: center; }
.bar-fill { width: min(34px, 70%); min-height: 5px; background: linear-gradient(180deg, var(--kb-accent), var(--kb-primary)); border-radius: 4px 4px 0 0; transition: height 0.2s ease; }
.document-list { padding: 6px 0; }
.document-row { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 12px 18px; color: var(--kb-text); text-align: left; background: transparent; border: 0; border-bottom: 1px solid var(--kb-border); cursor: pointer; transition: background 0.14s; }
.document-row:last-child { border-bottom: 0; }
.document-row:hover { background: var(--kb-surface-2); }
.document-main { min-width: 0; }
.document-main strong, .document-main small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.document-main strong { font-size: 13px; font-weight: 600; }
.document-main small { margin-top: 2px; color: var(--kb-text-muted); font-size: 11px; }
@media (max-width: 1100px) { .stat-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 900px) { .dashboard-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) {
  .stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .bar-chart { gap: 7px; padding-inline: 12px; }
}
</style>
