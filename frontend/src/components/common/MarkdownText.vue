<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '../../utils/markdown'

const props = defineProps({ text: { type: String, default: '' } })
const isRich = computed(() => /```|`|\*\*/.test(props.text || ''))
const html = computed(() => renderMarkdown(props.text))
</script>

<template>
  <span v-if="isRich" class="kb-md" v-html="html" />
  <span v-else class="kb-md-plain">{{ text }}</span>
</template>

<style scoped>
.kb-md { display: inline; overflow-wrap: anywhere; }
.kb-md-plain { display: inline; overflow-wrap: anywhere; white-space: pre-wrap; }
.kb-md :deep(.kb-md-p) { margin: 0 0 8px; }
.kb-md :deep(.kb-md-p:last-child) { margin-bottom: 0; }
.kb-md :deep(.kb-md-code) {
  padding: 1px 6px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  background: var(--kb-surface-2); border: 1px solid var(--kb-border); border-radius: 6px;
}
.kb-md :deep(.kb-md-pre) {
  margin: 8px 0; padding: 10px 12px; overflow-x: auto;
  background: #0f172a; color: #e2e8f0; border-radius: 8px; font-size: 12px; line-height: 1.7;
}
[data-theme="dark"] .kb-md :deep(.kb-md-pre) { background: #0b1120; }
.kb-md :deep(.kb-md-pre code) { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre; }
</style>
