<script setup>
defineProps({
  eyebrow: { type: String, default: '' },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
})
</script>

<template>
  <section class="page-head">
    <div class="page-head-main">
      <p v-if="eyebrow" class="page-eyebrow">{{ eyebrow }}</p>
      <h1 class="page-title">{{ title }}</h1>
      <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="page-head-actions"><slot name="actions" /></div>
  </section>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.page-eyebrow { margin-bottom: 5px; color: var(--kb-primary); font-size: 12px; font-weight: 700; letter-spacing: 0.4px; }
.page-title { font-size: 24px; font-weight: 700; letter-spacing: -0.2px; line-height: 1.3; }
.page-subtitle { margin-top: 5px; color: var(--kb-text-secondary); font-size: 13px; max-width: 760px; }
.page-head-actions { display: flex; gap: 8px; flex: none; }
@media (max-width: 720px) { .page-head { flex-direction: column; align-items: stretch; } }
</style>
