<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: 0 },
  hint: { type: String, default: '' },
  tone: { type: String, default: 'default' },
})
</script>

<template>
  <article class="stat-card" :class="`tone-${tone}`">
    <span class="stat-icon"><slot name="icon" /></span>
    <div class="stat-body">
      <span class="stat-label">{{ label }}</span>
      <strong class="stat-value">{{ value }}</strong>
      <span v-if="hint" class="stat-hint">{{ hint }}</span>
    </div>
  </article>
</template>

<style scoped>
.stat-card {
  display: flex; gap: 12px; align-items: flex-start;
  padding: 16px; background: var(--kb-surface);
  border: 1px solid var(--kb-border); border-radius: var(--kb-radius);
  box-shadow: var(--kb-shadow-sm); min-width: 0;
}
.stat-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 38px; height: 38px; flex: none;
  color: var(--kb-primary-strong); background: var(--kb-primary-soft);
  border-radius: 10px;
}
.tone-warning .stat-icon { color: var(--kb-warning); background: #fef3e2; }
.tone-success .stat-icon { color: var(--kb-success); background: #e9f7ef; }
.tone-danger .stat-icon { color: var(--kb-danger); background: #fdf0ef; }
[data-theme="dark"] .tone-warning .stat-icon { background: rgba(180,83,9,0.18); }
[data-theme="dark"] .tone-success .stat-icon { background: rgba(21,128,61,0.2); }
[data-theme="dark"] .tone-danger .stat-icon { background: rgba(216,53,42,0.2); }
.stat-body { min-width: 0; display: grid; gap: 2px; }
.stat-label { font-size: 12px; color: var(--kb-text-muted); white-space: nowrap; }
.stat-value { font-size: 24px; font-weight: 700; line-height: 1.2; }
.stat-hint { font-size: 11px; color: var(--kb-text-muted); }
</style>
