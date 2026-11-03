<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { kbApi } from '../api'
import { notifyError, notifySuccess } from '../utils/notify'
import MainLayout from '../layouts/MainLayout.vue'
import StatusTag from '../components/common/StatusTag.vue'

const route = useRoute()
const router = useRouter()
const kbId = computed(() => Number(route.params.kbId))
const kb = ref(null)
const docs = ref([])
const members = ref([])
const activeTab = ref(route.query.tab === 'members' ? 'members' : 'documents')
const documentKeyword = ref('')
const documentStatus = ref('ALL')
const loading = ref(false)
const docLoading = ref(false)
const uploadBusy = ref(false)
const memberBusy = ref(false)
const candidates = ref([])
const candidateLoading = ref(false)
const grantForm = reactive({ userId: null, role: 'VIEWER' })
let pollTimer = null

const hasRunningTasks = computed(() =>
  docs.value.some((doc) => ['PENDING', 'PROCESSING', 'DELETING'].includes(doc.status)),
)
const filteredDocs = computed(() => {
  const query = documentKeyword.value.trim().toLowerCase()
  return docs.value.filter((doc) => {
    if (documentStatus.value !== 'ALL' && doc.status !== documentStatus.value) return false
    if (!query) return true
    return [doc.name, doc.errorCode, doc.errorMsg]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(query))
  })
})

const statusMeta = {
  PENDING: { label: '排队中', type: 'info' },
  PROCESSING: { label: '处理中', type: 'warning' },
  ACTIVE: { label: '可用', type: 'success' },
  FAILED: { label: '处理失败', type: 'danger' },
  DELETING: { label: '删除中', type: 'warning' },
  DELETED: { label: '已删除', type: 'info' },
  DELETE_FAILED: { label: '删除失败', type: 'danger' },
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

function statusLabel(status) {
  return statusMeta[status]?.label || status
}

function statusType(status) {
  return statusMeta[status]?.type || 'info'
}

async function loadKb() {
  kb.value = await kbApi.get(kbId.value)
}

async function loadDocs(silent = false) {
  if (!silent) docLoading.value = true
  try {
    const data = await kbApi.documents(kbId.value)
    docs.value = data?.items || []
  } catch (err) {
    if (!silent) notifyError(err)
  } finally {
    docLoading.value = false
  }
}

async function loadMembers() {
  try {
    const data = await kbApi.members(kbId.value)
    members.value = data?.items || []
  } catch (err) {
    notifyError(err)
  }
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadKb(), loadDocs(), loadMembers()])
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}

async function uploadFile({ file, onSuccess, onError }) {
  if (uploadBusy.value) return
  uploadBusy.value = true
  try {
    const created = await kbApi.upload(kbId.value, file)
    notifySuccess(`「${file.name}」已进入处理队列`)
    onSuccess(created)
    await Promise.all([loadDocs(true), loadKb()])
  } catch (err) {
    onError(err)
    notifyError(err)
  } finally {
    uploadBusy.value = false
  }
}

async function retryDoc(doc) {
  try {
    await kbApi.retryDocument(kbId.value, doc.id)
    notifySuccess('已重新加入处理队列')
    await loadDocs(true)
  } catch (err) {
    notifyError(err)
  }
}

async function deleteDoc(doc) {
  try {
    await ElMessageBox.confirm(`确定删除「${doc.name}」？向量与文件将异步清理。`, '删除文档', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await kbApi.deleteDocument(kbId.value, doc.id)
    notifySuccess('删除任务已创建')
    await Promise.all([loadDocs(true), loadKb()])
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') notifyError(err)
  }
}

function openDoc(doc) {
  router.push({
    name: 'document-detail',
    params: { kbId: kbId.value, docId: doc.id },
  })
}

async function searchCandidates(keyword) {
  if (!kb.value?.canManage) return
  candidateLoading.value = true
  try {
    candidates.value = await kbApi.memberCandidates(kbId.value, keyword)
  } catch (err) {
    notifyError(err)
  } finally {
    candidateLoading.value = false
  }
}

async function grantMember() {
  if (!grantForm.userId || memberBusy.value) return
  memberBusy.value = true
  try {
    await kbApi.grantMember(kbId.value, {
      userId: grantForm.userId,
      role: grantForm.role,
    })
    notifySuccess('成员权限已保存')
    grantForm.userId = null
    grantForm.role = 'VIEWER'
    candidates.value = []
    await loadMembers()
  } catch (err) {
    notifyError(err)
  } finally {
    memberBusy.value = false
  }
}

async function changeRole(member, role) {
  try {
    await kbApi.updateMember(kbId.value, member.userId, role)
    notifySuccess('成员角色已更新')
    await loadMembers()
  } catch (err) {
    notifyError(err)
    await loadMembers()
  }
}

async function revokeMember(member) {
  try {
    await ElMessageBox.confirm(`移除成员「${member.nickname || member.username}」？`, '移除成员', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消',
    })
    await kbApi.revokeMember(kbId.value, member.userId)
    notifySuccess('成员已移除')
    await loadMembers()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') notifyError(err)
  }
}

function startPolling() {
  pollTimer = window.setInterval(async () => {
    if (!hasRunningTasks.value) return
    await loadDocs(true)
    if (!hasRunningTasks.value) await loadKb()
  }, 2000)
}

watch(kbId, loadAll)
watch(
  () => route.query.tab,
  (tab) => {
    if (tab === 'members') activeTab.value = 'members'
  },
)
onMounted(() => {
  loadAll()
  startPolling()
})
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <MainLayout :title="kb?.name || '知识库'" :subtitle="kb?.description || '暂无描述'" eyebrow="知识库详情">
    <template #actions>
      <el-button @click="router.push({ name: 'knowledge-bases' })">返回知识库</el-button>
      <el-upload
        v-if="kb?.canWrite"
        accept=".txt,.md,.pdf,.docx"
        :show-file-list="false"
        :http-request="uploadFile"
        :disabled="uploadBusy"
      >
        <el-button type="primary" :loading="uploadBusy">上传文档</el-button>
      </el-upload>
    </template>
    <div v-loading="loading" class="detail-body">
      <el-tabs v-model="activeTab" class="detail-tabs">
        <el-tab-pane name="documents">
          <template #label>文档处理 <span class="tab-count">{{ docs.length }}</span></template>
          <section class="panel">
            <div class="panel-head">
              <div>
                <h2>文档处理</h2>
                <p>状态每 2 秒自动刷新；知识库管理者可管理全部文档，不按上传者隔离。</p>
              </div>
              <div class="doc-tools">
                <el-input
                  v-model="documentKeyword"
                  clearable
                  placeholder="搜索文档或错误"
                  class="doc-search"
                />
                <el-select v-model="documentStatus" class="status-filter">
                  <el-option label="全部状态" value="ALL" />
                  <el-option label="排队中" value="PENDING" />
                  <el-option label="处理中" value="PROCESSING" />
                  <el-option label="可用" value="ACTIVE" />
                  <el-option label="处理失败" value="FAILED" />
                  <el-option label="删除中" value="DELETING" />
                  <el-option label="删除失败" value="DELETE_FAILED" />
                </el-select>
                <el-button text :loading="docLoading" @click="loadDocs()">刷新</el-button>
              </div>
            </div>
            <el-table
              :data="filteredDocs"
              row-key="id"
              :empty-text="docs.length ? '没有符合条件的文档' : '尚未上传文档'"
            >
              <el-table-column label="文件" min-width="260">
                <template #default="{ row }">
                  <button type="button" class="doc-name" @click="openDoc(row)">
                    {{ row.name }}
                  </button>
                  <div class="doc-meta">
                    {{ row.ext.toUpperCase() }} · {{ formatBytes(row.sizeBytes) }} ·
                    {{ row.createdAt ? formatDate(row.createdAt) : '-' }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row }">
                  <el-tag size="small" :type="statusType(row.status)" effect="plain">
                    {{ statusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="分片" width="80" align="right">
                <template #default="{ row }">{{ row.chunkCount || 0 }}</template>
              </el-table-column>
              <el-table-column label="尝试" width="90" align="right">
                <template #default="{ row }">{{ row.attempt }}/{{ row.maxAttempt }}</template>
              </el-table-column>
              <el-table-column label="错误" min-width="180">
                <template #default="{ row }">
                  <span class="error-text">{{ row.errorMsg || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="142" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openDoc(row)">详情</el-button>
                  <el-button
                    v-if="kb?.canWrite && row.canRetry"
                    link
                    type="warning"
                    @click="retryDoc(row)"
                  >
                    重试
                  </el-button>
                  <el-button
                    v-if="kb?.canWrite && row.status !== 'DELETING'"
                    link
                    type="danger"
                    @click="deleteDoc(row)"
                  >
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </el-tab-pane>

        <el-tab-pane name="members">
          <template #label>成员权限 <span class="tab-count">{{ members.length }}</span></template>
          <section class="panel">
            <div class="panel-head">
              <div>
                <h2>访问成员</h2>
                <p>VIEWER 只能读取，EDITOR 可上传与删除文档，MANAGER 还可管理成员。</p>
              </div>
              <el-button text :loading="memberBusy" @click="loadMembers">刷新</el-button>
            </div>

            <div v-if="kb?.canManage" class="grant-bar">
              <el-select
                v-model="grantForm.userId"
                filterable
                remote
                clearable
                reserve-keyword
                :remote-method="searchCandidates"
                :loading="candidateLoading"
                placeholder="输入用户名或昵称查找"
                class="candidate-select"
              >
                <el-option
                  v-for="candidate in candidates"
                  :key="candidate.userId"
                  :label="`${candidate.nickname}（${candidate.username}）`"
                  :value="candidate.userId"
                />
              </el-select>
              <el-select v-model="grantForm.role" class="role-select">
                <el-option label="只读" value="VIEWER" />
                <el-option label="编辑" value="EDITOR" />
                <el-option label="管理" value="MANAGER" />
              </el-select>
              <el-button
                type="primary"
                :disabled="!grantForm.userId"
                :loading="memberBusy"
                @click="grantMember"
              >
                添加成员
              </el-button>
            </div>

            <el-table :data="members" row-key="userId" empty-text="暂无成员">
              <el-table-column label="成员" min-width="220">
                <template #default="{ row }">
                  <div class="member-name">{{ row.nickname }}</div>
                  <div class="doc-meta">{{ row.username }}</div>
                </template>
              </el-table-column>
              <el-table-column label="角色" width="180">
                <template #default="{ row }">
                  <el-select
                    v-if="kb?.canManage && row.userId !== kb.ownerId"
                    :model-value="row.role"
                    size="small"
                    @change="(role) => changeRole(row, role)"
                  >
                    <el-option label="只读" value="VIEWER" />
                    <el-option label="编辑" value="EDITOR" />
                    <el-option label="管理" value="MANAGER" />
                  </el-select>
                  <el-tag v-else size="small" effect="plain">
                    {{ row.role === 'MANAGER' ? '管理' : row.role === 'EDITOR' ? '编辑' : '只读' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="加入时间" width="190">
                <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" fixed="right">
                <template #default="{ row }">
                  <span v-if="row.userId === kb?.ownerId" class="owner-label">所有者</span>
                  <el-button
                    v-else-if="kb?.canManage"
                    link
                    type="danger"
                    @click="revokeMember(row)"
                  >
                    移除
                  </el-button>
                  <span v-else class="owner-label">-</span>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </el-tab-pane>
      </el-tabs>
    </div>
  </MainLayout>
</template>

<style scoped>
.detail-body { display: grid; gap: 12px; }
.detail-tabs {
  padding: 0 18px 18px;
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
}

.tab-count {
  margin-left: 4px;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 4px 0 14px;
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

.doc-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.doc-search {
  width: 210px;
}

.status-filter {
  width: 120px;
}

.doc-name {
  padding: 0;
  color: var(--kb-text);
  background: none;
  border: 0;
  font: inherit;
  font-weight: 550;
  cursor: pointer;
}

.doc-name:hover {
  color: var(--kb-primary);
}

.doc-meta {
  margin-top: 2px;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.error-text {
  display: -webkit-box;
  overflow: hidden;
  color: #b5473c;
  font-size: 12px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.grant-bar {
  display: flex;
  gap: 8px;
  padding: 12px;
  margin-bottom: 12px;
  background: #f8f9fb;
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
}

.candidate-select {
  width: min(360px, 44vw);
}

.role-select {
  width: 110px;
}

.member-name {
  font-weight: 550;
}

.owner-label {
  color: var(--kb-text-muted);
  font-size: 12px;
}

@media (max-width: 720px) {
  .content {
    width: calc(100% - 20px);
    padding-top: 18px;
  }

  .heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .detail-tabs {
    padding: 0 10px 12px;
  }

  .grant-bar {
    flex-wrap: wrap;
  }

  .candidate-select {
    width: 100%;
  }

  .panel-head,
  .doc-tools {
    align-items: stretch;
    flex-direction: column;
  }

  .doc-search,
  .status-filter {
    width: 100%;
  }
}
</style>
