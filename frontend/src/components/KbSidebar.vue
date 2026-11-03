<script setup>
import { reactive, ref } from 'vue'
import { kbApi } from '../api'
import { notifyError, notifySuccess, notifyWarning } from '../utils/notify'

const props = defineProps({
  kbs: { type: Array, default: () => [] },
  activeId: { type: [Number, String], default: null },
  kbLoading: { type: Boolean, default: false },
  docs: { type: Array, default: () => [] },
  docLoading: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false },
  uploadName: { type: String, default: '' },
  canWrite: { type: Boolean, default: false },
})

const emit = defineEmits(['select', 'upload', 'created'])

const MAX_SIZE = 20 * 1024 * 1024

const fileInput = ref(null)
const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref(null)
const createForm = reactive({ name: '', description: '' })

const createRules = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { max: 128, message: '名称不能超过 128 字', trigger: 'blur' },
  ],
  description: [{ max: 1024, message: '描述不能超过 1024 字', trigger: 'blur' }],
}

function openCreate() {
  createForm.name = ''
  createForm.description = ''
  createVisible.value = true
}

async function submitCreate() {
  if (creating.value || !createFormRef.value) return
  try {
    await createFormRef.value.validate()
  } catch (e) {
    return
  }
  creating.value = true
  try {
    const data = await kbApi.create({
      name: createForm.name.trim(),
      description: createForm.description.trim() || undefined,
    })
    createVisible.value = false
    notifySuccess('知识库已创建')
    emit('created', data)
  } catch (err) {
    notifyError(err)
  } finally {
    creating.value = false
  }
}

function pickFile() {
  if (!props.activeId || !props.canWrite || props.uploading) return
  fileInput.value && fileInput.value.click()
}

function onFileChange(event) {
  const file = event.target.files && event.target.files[0]
  event.target.value = ''
  if (!file) return

  const name = file.name || ''
  if (!/\.(txt|md|pdf|docx)$/i.test(name)) {
    notifyWarning('仅支持 TXT、MD、PDF、DOCX 文件')
    return
  }
  if (file.size === 0) {
    notifyWarning('文件内容为空，请选择有效文件')
    return
  }
  if (file.size > MAX_SIZE) {
    notifyWarning('文件超过 20 MB 上限')
    return
  }
  emit('upload', file)
}

const STATUS_TEXT = {
  ACTIVE: '已入库',
  FAILED: '失败',
  PROCESSING: '处理中',
  PENDING: '排队中',
  DELETING: '删除中',
  DELETED: '已删除',
  DELETE_FAILED: '删除失败',
}

function statusText(status) {
  return STATUS_TEXT[status] || status || '未知'
}

function statusClass(status) {
  if (status === 'ACTIVE') return 'ok'
  if (status === 'FAILED' || status === 'DELETE_FAILED') return 'bad'
  return 'pending'
}

function formatSize(bytes) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n <= 0) return '0 KB'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <aside class="sidebar">
    <div class="sb-block sb-block-kb">
      <div class="sb-head">
        <span class="sb-title">知识库</span>
        <button class="sb-action" type="button" :disabled="kbLoading" @click="openCreate">
          <span class="plus">＋</span>新建
        </button>
      </div>

      <div class="kb-list dark-scroll">
        <p v-if="kbLoading && !kbs.length" class="sb-tip">正在加载知识库…</p>
        <p v-else-if="!kbs.length" class="sb-tip">
          还没有知识库。<br />点击右上角「新建」创建第一个。
        </p>
        <button
          v-for="kb in kbs"
          :key="kb.id"
          type="button"
          class="kb-item"
          :class="{ active: kb.id === activeId }"
          :title="kb.name"
          @click="emit('select', kb)"
        >
          <span class="kb-item-name">{{ kb.name }}</span>
          <span class="kb-item-count">{{ kb.docCount }}</span>
        </button>
      </div>
    </div>

    <div class="sb-divider"></div>

    <div class="sb-block sb-block-docs">
      <div class="sb-head">
        <span class="sb-title">
          文档
          <span v-if="activeId" class="sb-count">{{ docs.length }}</span>
        </span>
        <button
          v-if="canWrite"
          type="button"
          class="sb-action"
          :disabled="!activeId || uploading"
          @click="pickFile"
        >
          <span class="plus">＋</span>上传文档
        </button>
        <input
          ref="fileInput"
          class="hidden-file"
          type="file"
          accept=".txt,.md,.pdf,.docx"
          @change="onFileChange"
        />
      </div>

      <div class="doc-list dark-scroll">
        <div v-if="uploading" class="uploading-row">
          <span class="kb-spinner"></span>
          <span class="uploading-text">
            正在上传<span v-if="uploadName"> {{ uploadName }}</span>…
          </span>
        </div>

        <p v-if="!activeId" class="sb-tip">请先选择一个知识库。</p>
        <p v-else-if="docLoading && !docs.length" class="sb-tip">正在加载文档…</p>
        <p v-else-if="!docs.length && !uploading" class="sb-tip">
          该库暂无文档，上传文档后即可提问。
        </p>

        <div
          v-for="doc in docs"
          :key="doc.id"
          class="doc-item"
          :class="{ failed: doc.status === 'FAILED' }"
        >
          <div class="doc-row">
            <span class="doc-name" :title="doc.name">{{ doc.name }}</span>
            <span class="doc-tag" :class="statusClass(doc.status)">{{ statusText(doc.status) }}</span>
          </div>
          <div class="doc-meta">
            <span>{{ formatSize(doc.sizeBytes) }}</span>
            <template v-if="doc.status === 'ACTIVE'">
              <span class="dot">·</span>
              <span>{{ doc.chunkCount }} 分片</span>
            </template>
            <template v-else-if="doc.status === 'FAILED'">
              <span class="dot">·</span>
              <span class="doc-error" :title="doc.errorMsg">{{ doc.errorMsg || '处理失败' }}</span>
            </template>
          </div>
        </div>
      </div>
    </div>

    <el-dialog
      v-model="createVisible"
      title="新建知识库"
      width="440px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
        @submit.prevent="submitCreate"
      >
        <el-form-item label="名称" prop="name">
          <el-input
            v-model="createForm.name"
            maxlength="128"
            show-word-limit
            placeholder="例如：产品文档"
          />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            maxlength="1024"
            show-word-limit
            placeholder="可选，说明这个知识库存放哪些资料"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="creating" @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 300px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--kb-sidebar);
  color: var(--kb-sidebar-text);
  border-right: 1px solid var(--kb-sidebar-border);
  border-radius: 12px;
  height: 100%;
  overflow: hidden;
}

.sb-block {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.sb-block-kb {
  flex: 0 0 auto;
  max-height: 45%;
  padding: 16px 8px 8px;
}

.sb-block-docs {
  flex: 1 1 auto;
  padding: 8px 8px 16px;
}

.sb-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 8px 8px;
  min-height: 24px;
}

.sb-title {
  font-size: 13px;
  font-weight: 600;
  color: #eef1f7;
  letter-spacing: 0.2px;
}

.sb-count {
  margin-left: 4px;
  font-weight: 400;
  color: var(--kb-sidebar-muted);
}

.sb-action {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px 8px;
  font-size: 12px;
  line-height: 20px;
  color: #cfe0ff;
  background: rgba(47, 111, 237, 0.16);
  border: 1px solid rgba(47, 111, 237, 0.4);
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, opacity 0.15s;
}

.sb-action:hover:not(:disabled) {
  background: rgba(47, 111, 237, 0.28);
  border-color: rgba(47, 111, 237, 0.65);
}

.sb-action:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.plus {
  font-size: 12px;
  line-height: 1;
}

.kb-list {
  min-height: 0;
  overflow-y: auto;
}

.kb-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  height: 40px;
  padding: 0 8px;
  margin-bottom: 2px;
  font-size: 14px;
  color: var(--kb-sidebar-text);
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: var(--kb-radius);
  cursor: pointer;
}

.kb-item:hover {
  background: var(--kb-sidebar-hover);
}

.kb-item.active {
  background: var(--kb-sidebar-active);
  color: #ffffff;
}

.kb-item-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.kb-item-count {
  flex: none;
  min-width: 20px;
  padding: 0 4px;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  color: var(--kb-sidebar-muted);
  background: rgba(255, 255, 255, 0.06);
  border-radius: 9px;
}

.kb-item.active .kb-item-count {
  color: #dbe6ff;
  background: rgba(255, 255, 255, 0.14);
}

.sb-divider {
  height: 1px;
  margin: 0 16px;
  background: rgba(255, 255, 255, 0.08);
  flex: none;
}

.doc-list {
  min-height: 0;
  overflow-y: auto;
  padding: 0 0 8px;
}

.sb-tip {
  padding: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--kb-sidebar-muted);
}

.uploading-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  margin-bottom: 4px;
  font-size: 12px;
  color: #cfe0ff;
  background: rgba(47, 111, 237, 0.12);
  border-radius: var(--kb-radius);
}

.uploading-text {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.doc-item {
  padding: 6px 8px;
  margin-bottom: 2px;
  border-radius: var(--kb-radius);
  border-left: 2px solid transparent;
}

.doc-item:hover {
  background: var(--kb-sidebar-hover);
}

.doc-item.failed {
  border-left-color: var(--kb-danger);
}

.doc-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 28px;
}

.doc-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: #dde3ef;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.doc-tag {
  flex: none;
  padding: 0 6px;
  font-size: 11px;
  line-height: 18px;
  border-radius: 3px;
}

.doc-tag.ok {
  color: #9fe0b8;
  background: rgba(48, 160, 96, 0.18);
}

.doc-tag.bad {
  color: #ffb4ae;
  background: rgba(216, 53, 42, 0.2);
}

.doc-tag.pending {
  color: #ffd79a;
  background: rgba(214, 145, 34, 0.18);
}

.doc-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--kb-sidebar-muted);
}

.doc-error {
  color: #ffb4ae;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  max-width: 190px;
}

.hidden-file {
  display: none;
}

:deep(.el-dialog__body) {
  padding-top: 8px;
}

@media (max-width: 720px) {
  .sidebar {
    display: none;
  }
}
</style>
