<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { kbApi } from '../api'
import { notifyError, notifySuccess } from '../utils/notify'
import MainLayout from '../layouts/MainLayout.vue'
import StatusTag from '../components/common/StatusTag.vue'

const route = useRoute()
const router = useRouter()
const kbId = computed(() => Number(route.params.kbId))
const docId = computed(() => Number(route.params.docId))
const targetSeq = computed(() => {
  const value = Number(route.query.seq)
  return Number.isInteger(value) && value >= 0 ? value : null
})
const document = ref(null)
const kb = ref(null)
const chunks = ref([])
const chunkTotal = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const chunkLoading = ref(false)
const actionBusy = ref(false)
let pollTimer = null

const running = computed(() =>
  ['PENDING', 'PROCESSING', 'DELETING'].includes(document.value?.status),
)

const statusMeta = {
  PENDING: { label: '排队中', type: 'info', detail: '任务已入队，等待处理进程认领。' },
  PROCESSING: { label: '处理中', type: 'warning', detail: '正在解析文档并写入向量索引。' },
  ACTIVE: { label: '可用', type: 'success', detail: '文档已完成解析与索引，可参与问答。' },
  FAILED: { label: '处理失败', type: 'danger', detail: '处理失败，可按错误信息修正后重试。' },
  DELETING: { label: '删除中', type: 'warning', detail: '正在清理原始文件和向量。' },
  DELETE_FAILED: { label: '删除失败', type: 'danger', detail: '清理未完成，系统会自动补偿或允许人工重试。' },
  DELETED: { label: '已删除', type: 'info', detail: '文档已软删除。' },
}

function meta() {
  return statusMeta[document.value?.status] || { label: document.value?.status || '-', type: 'info', detail: '' }
}

function formatBytes(value) {
  const bytes = Number(value || 0)
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    const [docData, kbData] = await Promise.all([
      kbApi.document(kbId.value, docId.value),
      kbApi.get(kbId.value),
    ])
    document.value = docData
    kb.value = kbData
    if (Number(docData.chunkCount || 0) > 0) {
      if (targetSeq.value != null) {
        page.value = Math.floor(targetSeq.value / pageSize.value) + 1
      }
      await loadChunks(true)
    }
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}

async function poll() {
  if (!document.value || !running.value) return
  try {
    const latest = await kbApi.document(kbId.value, docId.value)
    const wasRunning = running.value
    document.value = latest
    if (wasRunning && !running.value && Number(latest.chunkCount || 0) > 0) {
      await loadChunks(true)
    }
  } catch (err) {
    if (err.httpStatus !== 404) notifyError(err)
  }
}

async function loadChunks(silent = false) {
  if (!silent) chunkLoading.value = true
  try {
    const data = await kbApi.chunks(kbId.value, docId.value, {
      page: page.value,
      size: pageSize.value,
    })
    chunks.value = data?.items || []
    chunkTotal.value = data?.total || 0
    if (targetSeq.value != null) {
      await nextTick()
      const element = document.getElementById(`chunk-${targetSeq.value}`)
      if (element) {
        element.scrollIntoView({ block: 'center', behavior: 'smooth' })
        element.classList.add('highlight')
        window.setTimeout(() => element.classList.remove('highlight'), 2200)
      }
    }
  } catch (err) {
    if (!silent) notifyError(err)
  } finally {
    chunkLoading.value = false
  }
}

async function retry() {
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    document.value = await kbApi.retryDocument(kbId.value, docId.value)
    notifySuccess('已重新加入处理队列')
  } catch (err) {
    notifyError(err)
  } finally {
    actionBusy.value = false
  }
}

async function remove() {
  try {
    await ElMessageBox.confirm('删除后原始文件和向量索引将异步清理，确定继续？', '删除文档', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    document.value = await kbApi.deleteDocument(kbId.value, docId.value)
    notifySuccess('删除任务已创建')
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') notifyError(err)
  }
}

async function download() {
  try {
    const blob = await kbApi.downloadDocument(kbId.value, docId.value)
    const url = URL.createObjectURL(blob)
    const anchor = window.document.createElement('a')
    anchor.href = url
    anchor.download = document.value.name
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (err) {
    notifyError(err)
  }
}

watch(page, () => loadChunks())
onMounted(() => {
  load()
  pollTimer = window.setInterval(poll, 2000)
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <MainLayout :title="document?.name || '文档详情'" :subtitle="meta().detail" eyebrow="文档处理">
    <template #actions>
      <el-button @click="router.push({ name: 'knowledge-base-detail', params: { kbId } })">
        返回{{ kb?.name || '知识库' }}
      </el-button>
          <el-button
            v-if="kb?.canWrite && document.canRetry"
            type="warning"
            plain
            :loading="actionBusy"
            @click="retry"
          >
            重试处理
          </el-button>
      <el-button v-if="kb?.canRead !== false" @click="download">下载原文件</el-button>
          <el-button
            v-if="kb?.canWrite && document.status !== 'DELETING'"
            type="danger"
            plain
            @click="remove"
          >
            删除文档
          </el-button>
    </template>
    <div v-loading="loading" class="detail-body">
      <section v-if="document" class="doc-status-line">
        <StatusTag :status="document.status" />
        <span class="doc-status-detail">{{ meta().detail }}</span>
      </section>

      <section v-if="document?.errorMsg" class="error-panel">
        <strong>{{ document.errorCode || '处理失败' }}</strong>
        <p>{{ document.errorMsg }}</p>
      </section>

      <section v-if="document" class="metadata">
        <div>
          <span>格式</span>
          <strong>{{ document.ext.toUpperCase() }}</strong>
        </div>
        <div>
          <span>文件大小</span>
          <strong>{{ formatBytes(document.sizeBytes) }}</strong>
        </div>
        <div>
          <span>分片数量</span>
          <strong>{{ document.chunkCount }}</strong>
        </div>
        <div>
          <span>任务尝试</span>
          <strong>{{ document.attempt }} / {{ document.maxAttempt }}</strong>
        </div>
        <div>
          <span>创建时间</span>
          <strong>{{ formatDate(document.createdAt) }}</strong>
        </div>
      </section>

      <section class="chunk-panel">
        <div class="panel-head">
          <div>
            <h2>分片预览</h2>
            <p>共 {{ chunkTotal }} 个分片，内容按原始文本安全渲染。</p>
          </div>
          <el-button text :loading="chunkLoading" @click="loadChunks()">刷新</el-button>
        </div>

        <el-empty
          v-if="!chunkLoading && chunks.length === 0"
          :description="running ? '文档处理完成后可预览分片' : '暂无分片'"
        />
        <div v-else v-loading="chunkLoading" class="chunk-list">
          <article
            v-for="chunk in chunks"
            :id="`chunk-${chunk.seq}`"
            :key="chunk.id"
            class="chunk-item"
          >
            <div class="chunk-head">
              <span>#{{ chunk.seq + 1 }}</span>
              <span v-if="chunk.pageNo != null">第 {{ chunk.pageNo }} 页</span>
              <span v-if="chunk.heading">{{ chunk.heading }}</span>
              <span v-if="chunk.tokenCount != null">{{ chunk.tokenCount }} 字符</span>
            </div>
            <p>{{ chunk.content }}</p>
          </article>
        </div>

        <el-pagination
          v-if="chunkTotal > pageSize"
          v-model:current-page="page"
          :page-size="pageSize"
          :total="chunkTotal"
          layout="prev, pager, next"
          class="pagination"
        />
      </section>
    </div>
  </MainLayout>
</template>

<style scoped>
.detail-body { display: grid; gap: 12px; }
.doc-status-line { display: flex; align-items: center; gap: 10px; }
.doc-status-detail { font-size: 13px; color: var(--kb-text-secondary); }

.error-panel {
  padding: 12px 14px;
  margin-bottom: 14px;
  color: #8a2d25;
  background: #fff7f5;
  border: 1px solid #f1c9c4;
  border-left: 3px solid var(--kb-danger);
  border-radius: var(--kb-radius);
}

.error-panel strong {
  font-size: 12px;
}

.error-panel p {
  margin-top: 3px;
  font-size: 13px;
  white-space: pre-wrap;
}

.metadata {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  margin-bottom: 14px;
  background: var(--kb-border);
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
}

.metadata > div {
  min-width: 0;
  padding: 12px 14px;
  background: var(--kb-surface);
}

.metadata span {
  display: block;
  margin-bottom: 3px;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.metadata strong {
  display: block;
  overflow: hidden;
  font-size: 13px;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chunk-panel {
  padding: 18px;
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}

.panel-head h2 {
  font-size: 14px;
  font-weight: 600;
}

.panel-head p {
  margin-top: 2px;
  color: var(--kb-text-muted);
  font-size: 12px;
}

.chunk-list {
  min-height: 80px;
}

.chunk-item {
  padding: 13px 14px;
  background: #fbfcfe;
  border: 1px solid var(--kb-border);
  scroll-margin-top: 24px;
  transition: background 0.2s, border-color 0.2s, box-shadow 0.2s;
}

.chunk-item + .chunk-item {
  margin-top: 8px;
}

.chunk-item.highlight {
  background: #f2f7ff;
  border-color: #9fbcf0;
  box-shadow: 0 0 0 3px rgba(47, 111, 237, 0.11);
}

.chunk-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-bottom: 7px;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.chunk-head span:first-child {
  color: var(--kb-primary);
  font-weight: 650;
}

.chunk-item p {
  color: var(--kb-text-secondary);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.pagination {
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 760px) {
  .content {
    width: calc(100% - 20px);
    padding-top: 18px;
  }

  .metadata {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .actions {
    flex-wrap: wrap;
  }
}
</style>
