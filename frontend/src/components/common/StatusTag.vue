<script setup>
import { computed } from 'vue'

const props = defineProps({ status: { type: String, default: '' } })

const meta = computed(() => ({
  PENDING: { label: '排队中', cls: 'info' },
  PROCESSING: { label: '处理中', cls: 'warning' },
  ACTIVE: { label: '可用', cls: 'success' },
  FAILED: { label: '处理失败', cls: 'danger' },
  DELETING: { label: '删除中', cls: 'warning' },
  DELETED: { label: '已删除', cls: 'muted' },
  DELETE_FAILED: { label: '删除失败', cls: 'danger' },
}[props.status] || { label: props.status || '未知', cls: 'muted' }))
</script>

<template>
  <span class="kb-status" :class="meta.cls"><i />{{ meta.label }}</span>
</template>

<style scoped>
.kb-status {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 9px; font-size: 12px; font-weight: 500; white-space: nowrap;
  border-radius: 999px; border: 1px solid var(--kb-border); background: var(--kb-surface-2);
  color: var(--kb-text-secondary);
}
.kb-status i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.kb-status.success { color: var(--kb-success); background: #e9f7ef; border-color: #c9e8d5; }
.kb-status.warning { color: var(--kb-warning); background: #fdf3e3; border-color: #f3ddb8; }
.kb-status.danger { color: var(--kb-danger); background: #fdf0ef; border-color: #f2c6c1; }
.kb-status.info { color: #1d4ed8; background: #eaf0fe; border-color: #c9d8fb; }
.kb-status.muted { color: var(--kb-text-muted); }
[data-theme="dark"] .kb-status.success { background: rgba(21,128,61,0.18); border-color: rgba(21,128,61,0.4); }
[data-theme="dark"] .kb-status.warning { background: rgba(180,83,9,0.18); border-color: rgba(180,83,9,0.4); }
[data-theme="dark"] .kb-status.danger { background: rgba(216,53,42,0.18); border-color: rgba(216,53,42,0.4); }
[data-theme="dark"] .kb-status.info { background: rgba(79,70,229,0.2); border-color: rgba(79,70,229,0.45); }
</style>
