<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ExternalLink, FileText } from 'lucide-vue-next'

const props = defineProps({
  citation: { type: Object, required: true },
  active: { type: Boolean, default: false },
})
const emit = defineEmits(['open'])

const expanded = ref(false)
const overflow = ref(false)
const quoteEl = ref(null)

const scoreText = computed(() => {
  const n = Number(props.citation.score)
  return Number.isFinite(n) ? n.toFixed(3) : '-'
})
const quoteText = computed(
  () => props.citation.quote || (props.citation.accessible === false ? '当前无权查看引用内容' : ''),
)

async function measure() {
  if (expanded.value) return
  await nextTick()
  const el = quoteEl.value
  if (!el) return
  overflow.value = el.scrollHeight - el.clientHeight > 1
}

onMounted(measure)
watch(quoteText, () => {
  expanded.value = false
  measure()
})
watch(() => props.citation.rank, measure)

function toggle() {
  expanded.value = !expanded.value
}
</script>

<template>
  <div class="cite-card" :class="{ active }">
    <div class="cite-head">
      <span class="cite-rank">[{{ citation.rank }}]</span>
      <FileText :size="13" aria-hidden="true" class="cite-icon" />
      <span class="cite-doc" :title="citation.docName">{{ citation.docName }}</span>
      <span class="cite-seq">第 {{ citation.seq }} 段</span>
      <span class="cite-score" title="相关度得分">{{ scoreText }}</span>
    </div>
    <p ref="quoteEl" class="cite-quote" :class="{ clamped: !expanded }">{{ quoteText }}</p>
    <div class="cite-foot">
      <button v-if="overflow || expanded" type="button" class="cite-toggle" @click="toggle">
        {{ expanded ? '收起' : '展开全文' }}
      </button>
      <button
        type="button"
        class="cite-open"
        :disabled="citation.accessible === false"
        @click="emit('open')"
      >
        <ExternalLink :size="12" aria-hidden="true" />查看原文
      </button>
    </div>
  </div>
</template>

<style scoped>
.cite-card {
  padding: 10px 12px;
  background: var(--kb-surface-2);
  border: 1px solid var(--kb-border);
  border-left: 3px solid var(--kb-border-strong);
  border-radius: var(--kb-radius-sm);
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}

.cite-card + .cite-card {
  margin-top: 8px;
}

.cite-icon { flex: none; color: var(--kb-primary-strong); }

.cite-card.active {
  background: var(--kb-primary-soft);
  border-color: var(--kb-primary);
  border-left-color: var(--kb-primary);
  box-shadow: 0 0 0 2px var(--kb-primary-ring);
}

.cite-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  line-height: 1.6;
}

.cite-rank {
  flex: none;
  font-weight: 600;
  color: var(--kb-primary);
}

.cite-doc {
  flex: 0 1 auto;
  min-width: 0;
  font-weight: 500;
  color: var(--kb-text);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.cite-seq {
  flex: none;
  color: var(--kb-text-muted);
}

.cite-score {
  flex: none;
  margin-left: auto;
  padding: 0 6px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  color: var(--kb-text-secondary);
  background: #eef1f6;
  border-radius: 3px;
}

.cite-card.active .cite-score {
  background: rgba(47, 111, 237, 0.1);
}

.cite-quote {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--kb-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.cite-quote.clamped {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.cite-toggle {
  margin-top: 4px;
  padding: 0;
  font-size: 12px;
  color: var(--kb-primary);
  background: none;
  border: 0;
  cursor: pointer;
}

.cite-toggle:hover {
  text-decoration: underline;
}

.cite-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 4px;
}

.cite-open {
  margin-left: auto;
  padding: 0;
  color: var(--kb-primary);
  background: none;
  border: 0;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.cite-open:hover:not(:disabled) {
  text-decoration: underline;
}

.cite-open:disabled {
  color: var(--kb-text-muted);
  cursor: not-allowed;
}
</style>
