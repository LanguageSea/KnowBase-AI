<script setup>
defineProps({
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' },
  actionLabel: { type: String, default: '' },
})
const emit = defineEmits(['action'])
</script>

<template>
  <div class="empty-state">
    <span class="empty-icon"><slot name="icon" /></span>
    <strong>{{ title }}</strong>
    <p v-if="description">{{ description }}</p>
    <el-button v-if="actionLabel" type="primary" plain size="small" @click="emit('action')">
      {{ actionLabel }}
    </el-button>
  </div>
</template>

<style scoped>
.empty-state {
  display: grid; justify-items: center; gap: 6px;
  padding: 44px 18px; text-align: center;
  color: var(--kb-text-muted); font-size: 13px;
}
.empty-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 46px; height: 46px; margin-bottom: 6px;
  color: var(--kb-primary-strong); background: var(--kb-primary-soft);
  border: 1px dashed var(--kb-border-strong); border-radius: 12px; font-size: 20px;
}
.empty-state strong { color: var(--kb-text-secondary); font-size: 14px; }
.empty-state p { max-width: 420px; font-size: 12px; }
.empty-state .el-button { margin-top: 8px; }
</style>
