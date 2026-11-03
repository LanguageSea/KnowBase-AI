<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { notifyError, notifySuccess } from '../utils/notify'

const loading = ref(false)
const models = ref([])
const stats = ref(null)
const testingId = ref(null)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref(null)
const editingModel = ref(null)
const form = reactive({
  provider: 'deepseek',
  modelType: 'CHAT',
  modelName: 'deepseek-chat',
  baseUrl: 'https://api.deepseek.com',
  apiKey: '',
  clearApiKey: false,
  enabled: true,
  paramsText: '{\n  "temperature": 0.2,\n  "max_tokens": 2048\n}',
})

const rules = {
  provider: [{ required: true, message: '请输入 provider', trigger: 'blur' }],
  modelType: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
  modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  baseUrl: [
    { required: true, message: '请输入 Base URL', trigger: 'blur' },
    { max: 512, message: 'Base URL 不能超过 512 个字符', trigger: 'blur' },
  ],
}

const successRate = computed(() => {
  const value = stats.value
  if (!value?.totalCalls) return '0%'
  return `${Math.round((value.successCalls / value.totalCalls) * 100)}%`
})

function typeLabel(type) {
  return { CHAT: '对话', EMBEDDING: '向量', RERANK: '重排' }[type] || type
}

function typeTag(type) {
  return { CHAT: 'primary', EMBEDDING: 'success', RERANK: 'warning' }[type] || 'info'
}

function formatDate(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function formatPercent(value) {
  return `${Math.round(Number(value || 0) * 100)}%`
}

async function load() {
  loading.value = true
  try {
    const [modelData, statData] = await Promise.all([
      adminApi.listModels(),
      adminApi.modelStats(7),
    ])
    models.value = modelData?.items || []
    stats.value = statData
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}

function resetForm(model = null) {
  editingModel.value = model
  Object.assign(form, model
    ? {
        provider: model.provider,
        modelType: model.modelType,
        modelName: model.modelName,
        baseUrl: model.baseUrl,
        apiKey: '',
        clearApiKey: false,
        enabled: model.enabled,
        paramsText: JSON.stringify(model.params || {}, null, 2),
      }
    : {
        provider: 'deepseek',
        modelType: 'CHAT',
        modelName: 'deepseek-chat',
        baseUrl: 'https://api.deepseek.com',
        apiKey: '',
        clearApiKey: false,
        enabled: true,
        paramsText: '{\n  "temperature": 0.2,\n  "max_tokens": 2048\n}',
      })
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

function parseParams() {
  const value = JSON.parse(form.paramsText || '{}')
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    throw new Error('params 必须是 JSON 对象')
  }
  return value
}

async function save() {
  if (!formRef.value || saving.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  let params
  try {
    params = parseParams()
  } catch (err) {
    notifyError(err)
    return
  }
  saving.value = true
  const payload = {
    provider: form.provider.trim(),
    modelType: form.modelType,
    modelName: form.modelName.trim(),
    baseUrl: form.baseUrl.trim(),
    apiKey: form.apiKey.trim() || null,
    clearApiKey: Boolean(editingModel.value && form.clearApiKey),
    params,
    enabled: form.enabled,
  }
  try {
    if (editingModel.value) {
      await adminApi.updateModel(editingModel.value.id, payload)
      notifySuccess('模型配置已更新')
    } else {
      await adminApi.createModel(payload)
      notifySuccess('模型配置已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (err) {
    notifyError(err)
  } finally {
    saving.value = false
  }
}

async function testModel(model) {
  if (testingId.value) return
  testingId.value = model.id
  try {
    const result = await adminApi.testModel(model.id)
    if (result?.success) {
      notifySuccess(`连接成功，耗时 ${result.latencyMs} ms`)
    } else {
      notifyError(new Error(`${result?.message || '连接失败'}（${result?.errorType || 'UNKNOWN'}）`))
    }
    await load()
  } catch (err) {
    notifyError(err)
  } finally {
    testingId.value = null
  }
}

async function removeModel(model) {
  try {
    await ElMessageBox.confirm(
      `删除模型「${model.modelName}」后，运行中的调用将回退到环境变量配置。`,
      '删除模型配置',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await adminApi.deleteModel(model.id)
    notifySuccess('模型配置已删除')
    await load()
  } catch (err) {
    notifyError(err)
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="models-panel">
    <section class="stats-strip">
      <div>
        <span>近 7 天调用</span>
        <strong>{{ stats?.totalCalls ?? 0 }}</strong>
      </div>
      <div>
        <span>成功率</span>
        <strong>{{ successRate }}</strong>
      </div>
      <div>
        <span>失败调用</span>
        <strong>{{ stats?.failedCalls ?? 0 }}</strong>
      </div>
      <div class="stats-actions">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" @click="resetForm()">新增模型</el-button>
      </div>
    </section>

    <section class="table-panel">
      <div class="panel-head">
        <div>
          <h2>模型服务</h2>
          <p>启用的同类模型按最近更新时间选用，未配置时回退到环境变量。</p>
        </div>
      </div>
      <el-table :data="models" row-key="id" empty-text="尚未配置模型">
        <el-table-column label="模型" min-width="220">
          <template #default="{ row }">
            <div class="model-name">{{ row.modelName }}</div>
            <div class="model-meta">{{ row.provider }} · {{ typeLabel(row.modelType) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" min-width="250" show-overflow-tooltip />
        <el-table-column label="API Key" width="170">
          <template #default="{ row }">
            <code v-if="row.maskedApiKey">{{ row.maskedApiKey }}</code>
            <el-tag v-else size="small" type="info" effect="plain">
              {{ row.keyStatus === 'UNREADABLE' ? '无法解密' : '未设置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="plain">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近测试" width="180">
          <template #default="{ row }">
            <el-tag
              v-if="row.lastTestOk !== null && row.lastTestOk !== undefined"
              size="small"
              :type="row.lastTestOk ? 'success' : 'danger'"
              effect="plain"
            >
              {{ row.lastTestOk ? '通过' : '失败' }}
            </el-tag>
            <div class="test-time">{{ formatDate(row.lastTestAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="215" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testingId === row.id" @click="testModel(row)">
              测试
            </el-button>
            <el-button link type="primary" @click="resetForm(row)">编辑</el-button>
            <el-button link type="danger" @click="removeModel(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section v-if="stats?.byModel?.length" class="statistics-grid">
      <div class="table-panel">
        <div class="panel-head"><h2>按模型聚合</h2></div>
        <el-table :data="stats.byModel" size="small">
          <el-table-column label="模型" min-width="180">
            <template #default="{ row }">{{ row.modelName }}</template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.modelType)" effect="plain">
                {{ typeLabel(row.modelType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="calls" label="调用" width="80" align="right" />
          <el-table-column label="成功率" width="90" align="right">
            <template #default="{ row }">{{ formatPercent(row.successRate) }}</template>
          </el-table-column>
          <el-table-column label="平均耗时" width="105" align="right">
            <template #default="{ row }">{{ row.avgLatencyMs }} ms</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="table-panel">
        <div class="panel-head"><h2>按天聚合</h2></div>
        <div class="daily-list">
          <div v-for="item in stats.daily" :key="item.day" class="daily-row">
            <span>{{ item.day }}</span>
            <span>{{ item.calls }} 次</span>
            <span>{{ formatPercent(item.calls ? item.successes / item.calls : 0) }}</span>
          </div>
        </div>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingModel ? '编辑模型配置' : '新增模型配置'"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="Provider" prop="provider">
            <el-input v-model="form.provider" placeholder="deepseek / openai / ollama" />
          </el-form-item>
          <el-form-item label="模型类型" prop="modelType">
            <el-select v-model="form.modelType">
              <el-option label="对话模型" value="CHAT" />
              <el-option label="向量模型" value="EMBEDDING" />
              <el-option label="重排模型" value="RERANK" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型名称" prop="modelName">
            <el-input v-model="form.modelName" placeholder="deepseek-chat" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </div>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="editingModel ? `留空保持原值（${editingModel.maskedApiKey || '未设置'}）` : '本地 Ollama 可留空'"
          />
          <el-checkbox v-if="editingModel" v-model="form.clearApiKey" class="clear-key">
            清除已保存的 API Key
          </el-checkbox>
        </el-form-item>
        <el-form-item label="扩展参数（JSON）">
          <el-input v-model="form.paramsText" type="textarea" :rows="5" spellcheck="false" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.models-panel {
  min-height: 220px;
}

.stats-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(120px, 1fr)) auto;
  margin-bottom: 14px;
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
}

.stats-strip > div {
  padding: 13px 16px;
}

.stats-strip > div + div {
  border-left: 1px solid var(--kb-border);
}

.stats-strip span {
  display: block;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.stats-strip strong {
  font-size: 20px;
  font-weight: 650;
}

.stats-strip .stats-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  border-left: 1px solid var(--kb-border);
}

.table-panel {
  margin-bottom: 14px;
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
  overflow: hidden;
}

.panel-head {
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 16px;
  border-bottom: 1px solid var(--kb-border);
}

.panel-head h2 {
  font-size: 14px;
  font-weight: 650;
}

.panel-head p {
  margin-top: 2px;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.model-name {
  font-weight: 600;
}

.model-meta,
.test-time {
  margin-top: 2px;
  color: var(--kb-text-muted);
  font-size: 11px;
}

code {
  color: var(--kb-text-secondary);
  font-size: 12px;
}

.statistics-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(280px, 0.6fr);
  gap: 14px;
}

.daily-list {
  padding: 6px 16px;
}

.daily-row {
  display: grid;
  grid-template-columns: 1fr auto 64px;
  gap: 12px;
  padding: 7px 0;
  color: var(--kb-text-secondary);
  font-size: 12px;
  border-bottom: 1px solid #f0f2f5;
}

.daily-row:last-child {
  border-bottom: 0;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.clear-key {
  margin-top: 5px;
}

@media (max-width: 900px) {
  .stats-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .stats-strip > div:nth-child(3),
  .stats-strip .stats-actions {
    border-left: 0;
    border-top: 1px solid var(--kb-border);
  }

  .stats-strip .stats-actions {
    grid-column: 1 / -1;
  }

  .statistics-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
