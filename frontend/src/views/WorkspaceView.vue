<script setup>
import { computed, onMounted, ref } from 'vue'
import { kbApi } from '../api'
import { notifyError, notifySuccess } from '../utils/notify'
import MainLayout from '../layouts/MainLayout.vue'
import KbSidebar from '../components/KbSidebar.vue'
import ChatPanel from '../components/ChatPanel.vue'

const kbs = ref([])
const kbLoading = ref(false)
const activeKbId = ref(null)

const docs = ref([])
const docLoading = ref(false)

const uploading = ref(false)
const uploadName = ref('')
const sidebarMobileOpen = ref(false)

const activeKb = computed(
  () => kbs.value.find((kb) => String(kb.id) === String(activeKbId.value)) || null,
)
const totalDocumentCount = computed(() =>
  kbs.value.reduce((total, kb) => total + Number(kb.docCount || 0), 0),
)

async function loadKbs() {
  kbLoading.value = true
  try {
    const data = await kbApi.list()
    kbs.value = (data && data.items) || []
  } catch (err) {
    notifyError(err)
  } finally {
    kbLoading.value = false
  }
}

async function loadDocs(kbId) {
  if (!kbId) {
    docs.value = []
    return
  }
  docLoading.value = true
  docs.value = []
  try {
    const data = await kbApi.documents(kbId)
    if (String(kbId) === String(activeKbId.value)) {
      docs.value = (data && data.items) || []
    }
  } catch (err) {
    notifyError(err)
  } finally {
    docLoading.value = false
  }
}

function selectKb(kb) {
  if (String(kb.id) === String(activeKbId.value)) return
  activeKbId.value = kb.id
  loadDocs(kb.id)
}

function onCreated(kb) {
  loadKbs()
  if (kb && kb.id != null) {
    activeKbId.value = kb.id
    loadDocs(kb.id)
  }
}

async function onUpload(file) {
  if (!activeKbId.value || uploading.value) return
  uploading.value = true
  uploadName.value = file.name
  try {
    await kbApi.upload(activeKbId.value, file)
    notifySuccess(`「${file.name}」已提交处理`)
    await loadDocs(activeKbId.value)
    loadKbs()
  } catch (err) {
    notifyError(err)
  } finally {
    uploading.value = false
    uploadName.value = ''
  }
}

onMounted(() => {
  loadKbs()
})
</script>

<template>
  <MainLayout title="智能问答" subtitle="检索全部有权限的知识库，引用可追溯。" :hide-content-padding="true">
    <div class="workspace-body">
      <button
        type="button"
        class="sources-toggle"
        :aria-expanded="sidebarMobileOpen"
        @click="sidebarMobileOpen = !sidebarMobileOpen"
      >
        {{ sidebarMobileOpen ? '收起知识库面板' : '展开知识库面板' }}
      </button>
      <div class="workspace-grid" :class="{ 'sources-open': sidebarMobileOpen }">
        <KbSidebar
          class="workspace-sources"
          :kbs="kbs"
          :active-id="activeKbId"
          :kb-loading="kbLoading"
          :docs="docs"
          :doc-loading="docLoading"
          :uploading="uploading"
          :upload-name="uploadName"
          :can-write="activeKb?.canWrite === true"
          @select="selectKb"
          @upload="onUpload"
          @created="onCreated"
        />
        <main class="workspace-main">
          <ChatPanel :kb-count="kbs.length" :doc-count="totalDocumentCount" />
        </main>
      </div>
    </div>
  </MainLayout>
</template>

<style scoped>
.workspace-body { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.workspace-grid { flex: 1; min-height: 0; display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 12px; align-items: stretch; overflow: hidden; }
.workspace-main { min-width: 0; min-height: 0; display: flex; overflow: hidden; background: var(--kb-surface); border: 1px solid var(--kb-border); border-radius: 12px; box-shadow: var(--kb-shadow-sm); }
.workspace-main > * { flex: 1; min-width: 0; min-height: 0; }
.workspace-sources { min-width: 0; min-height: 0; max-height: 100%; overflow: hidden; display: flex; }
.workspace-sources > * { flex: 1; min-height: 0; }
.sources-toggle { display: none; flex: none; width: 100%; margin-bottom: 10px; padding: 9px 12px; font: inherit; font-size: 13px; color: var(--kb-text-secondary); background: var(--kb-surface); border: 1px solid var(--kb-border); border-radius: 9px; cursor: pointer; }
@media (max-width: 980px) {
  .sources-toggle { display: block; }
  .workspace-grid { grid-template-columns: 1fr; align-content: start; overflow-y: auto; }
  .workspace-sources { display: none; }
  .workspace-grid.sources-open .workspace-sources { display: flex; max-height: 50dvh; }
  .workspace-main { min-height: 70dvh; }
}
</style>
