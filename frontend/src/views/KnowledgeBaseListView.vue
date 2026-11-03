<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Files, LibraryBig, Plus, Search } from 'lucide-vue-next'
import { kbApi } from '../api'
import { notifyError, notifySuccess } from '../utils/notify'
import MainLayout from '../layouts/MainLayout.vue'
import EmptyState from '../components/common/EmptyState.vue'
import StatusTag from '../components/common/StatusTag.vue'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const creating = ref(false)
const dialogVisible = ref(false)
const kbs = ref([])
const keyword = ref(String(route.query.keyword || ''))
const viewMode = ref('card')
const formRef = ref(null)
const form = reactive({ name: '', description: '' })
const rules = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { max: 128, message: '名称不能超过 128 个字符', trigger: 'blur' },
  ],
  description: [{ max: 1024, message: '描述不能超过 1024 个字符', trigger: 'blur' }],
}

const filteredKbs = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return kbs.value
  return kbs.value.filter((kb) =>
    [kb.name, kb.description].filter(Boolean).some((v) => String(v).toLowerCase().includes(query)),
  )
})
const totalDocs = computed(() => kbs.value.reduce((sum, kb) => sum + Number(kb.docCount || 0), 0))

function accessLabel(kb) {
  if (kb.canManage) return '管理'
  if (kb.canWrite) return '编辑'
  return '只读'
}

function formatDate(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    const data = await kbApi.list()
    kbs.value = data?.items || []
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

async function createKb() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  creating.value = true
  try {
    const created = await kbApi.create({
      name: form.name.trim(),
      description: form.description.trim() || null,
    })
    notifySuccess('知识库已创建')
    dialogVisible.value = false
    router.push({ name: 'knowledge-base-detail', params: { kbId: created.id } })
  } catch (err) {
    notifyError(err)
  } finally {
    creating.value = false
  }
}

function openKb(kb) {
  router.push({ name: 'knowledge-base-detail', params: { kbId: kb.id } })
}

function openPermissions(kb) {
  router.push({ name: 'knowledge-base-detail', params: { kbId: kb.id }, query: { tab: 'members' } })
}

onMounted(load)
</script>

<template>
  <MainLayout eyebrow="知识空间" title="知识库" subtitle="管理可访问的资料、成员与文档处理状态。">
    <template #actions>
      <el-button type="primary" @click="openCreate"><Plus :size="14" aria-hidden="true" />新建知识库</el-button>
    </template>

    <section class="summary-strip" aria-label="知识库概况">
      <div class="summary-item">
        <span class="summary-icon"><LibraryBig :size="16" aria-hidden="true" /></span>
        <div><span class="summary-label">可访问知识库</span><strong>{{ kbs.length }}</strong></div>
      </div>
      <div class="summary-item">
        <span class="summary-icon"><Files :size="16" aria-hidden="true" /></span>
        <div><span class="summary-label">可用文档</span><strong>{{ totalDocs }}</strong></div>
      </div>
      <div class="summary-search">
        <el-input v-model="keyword" clearable placeholder="搜索名称或描述" :prefix-icon="Search" />
        <el-segmented v-model="viewMode" :options="[{label:'卡片',value:'card'},{label:'表格',value:'table'}]" size="small" />
        <el-button text :loading="loading" @click="load">刷新</el-button>
      </div>
    </section>

    <div v-if="loading && !kbs.length" class="card-grid">
      <div v-for="n in 6" :key="n" class="kb-card card-skeleton"><div class="kb-skeleton" /><div class="kb-skeleton short" /><div class="kb-skeleton short" /></div>
    </div>
    <div v-else-if="viewMode === 'card'" class="card-grid">
      <article v-for="kb in filteredKbs" :key="kb.id" class="kb-card kb-entry" @click="openKb(kb)">
        <div class="kb-entry-head">
          <span class="kb-entry-icon"><LibraryBig :size="17" aria-hidden="true" /></span>
          <el-tag size="small" effect="plain">{{ accessLabel(kb) }}</el-tag>
        </div>
        <h2 class="kb-entry-name">{{ kb.name }}</h2>
        <p class="kb-entry-desc">{{ kb.description || '暂无描述' }}</p>
        <div class="kb-entry-foot">
          <span>{{ kb.docCount ?? 0 }} 篇文档</span>
          <span>{{ formatDate(kb.createdAt) }}</span>
        </div>
        <div class="kb-entry-actions" @click.stop>
          <el-button link type="primary" @click="openKb(kb)">文档</el-button>
          <el-button v-if="kb.canManage" link type="primary" @click="openPermissions(kb)">成员权限</el-button>
        </div>
      </article>
      <button v-if="!filteredKbs.length" type="button" class="kb-card kb-create" @click="openCreate">
        <Plus :size="18" aria-hidden="true" />
        <strong>创建第一个知识库</strong>
        <span>把散落的文档收拢成可检索的知识</span>
      </button>
    </div>
    <EmptyState
      v-if="!loading && viewMode === 'card' && !filteredKbs.length && kbs.length"
      title="没有符合条件的知识库"
      description="换个关键词试试。"
    >
      <template #icon><Search :size="20" aria-hidden="true" /></template>
    </EmptyState>

    <section v-if="viewMode === 'table'" class="kb-card table-panel">
      <el-table v-loading="loading" :data="filteredKbs" row-key="id" empty-text="还没有知识库">
        <el-table-column label="名称" min-width="240">
          <template #default="{ row }">
            <button type="button" class="name-link" @click="openKb(row)">{{ row.name }}</button>
            <p class="description">{{ row.description || '暂无描述' }}</p>
          </template>
        </el-table-column>
        <el-table-column label="权限" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.canManage ? 'success' : row.canWrite ? 'primary' : 'info'" effect="plain">
              {{ accessLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="docCount" label="可用文档" width="110" align="right" />
        <el-table-column label="创建时间" width="190">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openKb(row)">文档</el-button>
            <el-button v-if="row.canManage" link type="primary" @click="openPermissions(row)">成员权限</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" title="新建知识库" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="128" show-word-limit autofocus />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="1024" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createKb">创建</el-button>
      </template>
    </el-dialog>
  </MainLayout>
</template>

<style scoped>
.summary-strip { display: flex; align-items: center; gap: 24px; padding: 14px 18px; margin-bottom: 14px; background: var(--kb-surface); border: 1px solid var(--kb-border); border-radius: var(--kb-radius); box-shadow: var(--kb-shadow-sm); }
.summary-item { display: flex; align-items: center; gap: 10px; }
.summary-icon { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; color: var(--kb-primary-strong); background: var(--kb-primary-soft); border-radius: 9px; }
.summary-item div { display: grid; }
.summary-label { color: var(--kb-text-muted); font-size: 12px; }
.summary-item strong { font-size: 20px; font-weight: 700; }
.summary-search { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.summary-search .el-input { width: 240px; }
.card-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.card-skeleton { padding: 16px; display: grid; gap: 10px; }
.kb-skeleton.short { width: 62%; }
.kb-entry { padding: 16px; cursor: pointer; transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease; }
.kb-entry:hover { transform: translateY(-2px); box-shadow: var(--kb-shadow-md); border-color: var(--kb-border-strong); }
.kb-entry-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.kb-entry-icon { display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; color: #fff; background: linear-gradient(135deg, var(--kb-primary), var(--kb-accent)); border-radius: 10px; }
.kb-entry-name { font-size: 15px; font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kb-entry-desc { margin-top: 4px; min-height: 20px; overflow: hidden; color: var(--kb-text-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.kb-entry-foot { display: flex; justify-content: space-between; gap: 10px; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--kb-border); font-size: 11px; color: var(--kb-text-muted); }
.kb-entry-actions { display: flex; gap: 4px; margin-top: 8px; }
.kb-create { display: grid; justify-items: center; gap: 4px; padding: 28px 16px; color: var(--kb-text-secondary); border-style: dashed; cursor: pointer; background: transparent; font: inherit; }
.kb-create:hover { color: var(--kb-primary-strong); border-color: var(--kb-primary); background: var(--kb-primary-soft); }
.kb-create strong { font-size: 14px; }
.kb-create span { font-size: 12px; color: var(--kb-text-muted); }
.table-panel { overflow: hidden; }
.name-link { padding: 0; color: var(--kb-text); background: none; border: 0; font: inherit; font-weight: 600; cursor: pointer; }
.name-link:hover { color: var(--kb-primary); }
.description { max-width: 620px; margin-top: 2px; overflow: hidden; color: var(--kb-text-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1100px) { .card-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) {
  .summary-strip { flex-wrap: wrap; }
  .summary-search { width: 100%; margin-left: 0; flex-wrap: wrap; }
  .summary-search .el-input { flex: 1; min-width: 180px; width: auto; }
  .card-grid { grid-template-columns: 1fr; }
}
</style>
