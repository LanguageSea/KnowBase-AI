<script setup>
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '../api'
import { notifyError } from '../utils/notify'

const statusLoading = ref(false)
const logsLoading = ref(false)
const status = ref(null)
const logs = ref([])
const total = ref(0)
const filters = reactive({ action: '', result: '' })
const pagination = reactive({ page: 1, size: 20 })

function formatDate(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function componentType(component) {
  return component.status === 'UP' ? 'success' : 'danger'
}

async function loadStatus() {
  statusLoading.value = true
  try {
    status.value = await adminApi.systemStatus()
  } catch (err) {
    notifyError(err)
  } finally {
    statusLoading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const data = await adminApi.auditLogs({
      page: pagination.page,
      size: pagination.size,
      action: filters.action || undefined,
      result: filters.result || undefined,
    })
    logs.value = data?.items || []
    total.value = data?.total || 0
  } catch (err) {
    notifyError(err)
  } finally {
    logsLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadStatus(), loadLogs()])
}

function search() {
  pagination.page = 1
  loadLogs()
}

function resetFilters() {
  filters.action = ''
  filters.result = ''
  search()
}

onMounted(refreshAll)
</script>

<template>
  <div>
    <section v-loading="statusLoading" class="status-panel">
      <div class="status-head">
        <div>
          <h2>系统健康</h2>
          <p>依赖探活、任务积压和最近 7 天错误聚合。</p>
        </div>
        <div class="status-actions">
          <el-tag :type="status?.status === 'UP' ? 'success' : 'danger'" effect="plain">
            {{ status?.status || 'UNKNOWN' }}
          </el-tag>
          <el-button text :loading="statusLoading" @click="loadStatus">刷新状态</el-button>
        </div>
      </div>

      <div class="health-components">
        <div v-for="component in status?.components || []" :key="component.name" class="health-item">
          <div class="health-title">
            <strong>{{ component.name }}</strong>
            <el-tag size="small" :type="componentType(component)" effect="plain">
              {{ component.status }}
            </el-tag>
          </div>
          <span>{{ component.latencyMs }} ms</span>
          <small v-if="component.detail">{{ component.detail }}</small>
        </div>
      </div>

      <div class="backlog-grid">
        <div><span>排队任务</span><strong>{{ status?.backlog?.queued ?? 0 }}</strong></div>
        <div><span>运行任务</span><strong>{{ status?.backlog?.running ?? 0 }}</strong></div>
        <div><span>失败任务</span><strong>{{ status?.backlog?.failed ?? 0 }}</strong></div>
        <div><span>终止任务</span><strong>{{ status?.backlog?.dead ?? 0 }}</strong></div>
        <div><span>异常文档</span><strong>{{ status?.backlog?.failedDocuments ?? 0 }}</strong></div>
      </div>

      <div class="error-columns">
        <div>
          <h3>任务错误 Top5</h3>
          <div v-if="status?.taskErrorTop5?.length" class="error-list">
            <div v-for="item in status.taskErrorTop5" :key="item.code">
              <code>{{ item.code }}</code>
              <strong>{{ item.count }}</strong>
            </div>
          </div>
          <p v-else class="empty-copy">近 7 天无失败任务</p>
        </div>
        <div>
          <h3>模型错误 Top5</h3>
          <div v-if="status?.aiErrorTop5?.length" class="error-list">
            <div v-for="item in status.aiErrorTop5" :key="item.code">
              <code>{{ item.code }}</code>
              <strong>{{ item.count }}</strong>
            </div>
          </div>
          <p v-else class="empty-copy">近 7 天无失败模型调用</p>
        </div>
      </div>
    </section>

    <section class="table-panel">
      <div class="panel-head">
        <div>
          <h2>操作审计</h2>
          <p>登录、建库、删除文档和模型变更均会留痕。</p>
        </div>
        <el-button text :loading="logsLoading" @click="loadLogs">刷新日志</el-button>
      </div>
      <div class="filters">
        <el-select v-model="filters.action" clearable placeholder="全部动作">
          <el-option label="登录" value="LOGIN" />
          <el-option label="建知识库" value="KB_CREATE" />
          <el-option label="删文档" value="DOC_DELETE" />
          <el-option label="新建模型" value="MODEL_CREATE" />
          <el-option label="修改模型" value="MODEL_UPDATE" />
          <el-option label="测试模型" value="MODEL_TEST" />
          <el-option label="删除模型" value="MODEL_DELETE" />
        </el-select>
        <el-select v-model="filters.result" clearable placeholder="全部结果">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILURE" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>

      <el-table v-loading="logsLoading" :data="logs" row-key="id" empty-text="没有审计记录">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="130">
          <template #default="{ row }">{{ row.username || '系统 / 未认证' }}</template>
        </el-table-column>
        <el-table-column prop="action" label="动作" width="140" />
        <el-table-column label="资源" min-width="150">
          <template #default="{ row }">
            {{ row.resourceType }}{{ row.resourceId ? ` #${row.resourceId}` : '' }}
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.result === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
              {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="Trace ID" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.traceId || '-' }}</template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadLogs"
          @size-change="search"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.status-panel,
.table-panel {
  margin-bottom: 14px;
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
}

.status-head,
.panel-head {
  min-height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--kb-border);
}

.status-head h2,
.panel-head h2 {
  font-size: 14px;
  font-weight: 650;
}

.status-head p,
.panel-head p {
  margin-top: 2px;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.status-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.health-components {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.health-item {
  min-width: 0;
  padding: 14px 16px;
}

.health-item + .health-item {
  border-left: 1px solid var(--kb-border);
}

.health-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.health-title strong {
  text-transform: uppercase;
  font-size: 12px;
}

.health-item > span,
.health-item small {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  color: var(--kb-text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.backlog-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  background: #f8f9fb;
  border-top: 1px solid var(--kb-border);
  border-bottom: 1px solid var(--kb-border);
}

.backlog-grid > div {
  padding: 12px 16px;
}

.backlog-grid > div + div {
  border-left: 1px solid var(--kb-border);
}

.backlog-grid span {
  display: block;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.backlog-grid strong {
  font-size: 19px;
  font-weight: 650;
}

.error-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.error-columns > div {
  padding: 14px 16px;
}

.error-columns > div + div {
  border-left: 1px solid var(--kb-border);
}

.error-columns h3 {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
}

.error-list > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
  border-bottom: 1px solid #f0f2f5;
}

.error-list code {
  color: var(--kb-text-secondary);
  font-size: 11px;
}

.empty-copy {
  color: var(--kb-text-muted);
  font-size: 11px;
}

.filters {
  display: flex;
  gap: 8px;
  padding: 11px 16px;
  border-bottom: 1px solid var(--kb-border);
}

.filters :deep(.el-select) {
  width: 160px;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--kb-border);
}

@media (max-width: 900px) {
  .health-components {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .health-item:nth-child(3) {
    border-left: 0;
    border-top: 1px solid var(--kb-border);
  }

  .health-item:nth-child(4) {
    border-top: 1px solid var(--kb-border);
  }

  .backlog-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .backlog-grid > div:nth-child(4) {
    border-left: 0;
    border-top: 1px solid var(--kb-border);
  }

  .backlog-grid > div:nth-child(5) {
    border-top: 1px solid var(--kb-border);
  }
}

@media (max-width: 680px) {
  .error-columns {
    grid-template-columns: 1fr;
  }

  .error-columns > div + div {
    border-left: 0;
    border-top: 1px solid var(--kb-border);
  }

  .filters {
    align-items: stretch;
    flex-direction: column;
  }

  .filters :deep(.el-select) {
    width: 100%;
  }
}
</style>
