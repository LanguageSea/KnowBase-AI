<script setup>
import { Pencil, Plus, Trash2 } from 'lucide-vue-next'

defineProps({
  items: { type: Array, default: () => [] },
  activeId: { type: [Number, String], default: null },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['new', 'select', 'rename', 'delete'])
</script>

<template>
  <aside class="conversations">
    <div class="conv-head">
      <span>历史对话</span>
      <button type="button" class="new-conv" :disabled="disabled" @click="emit('new')">
        <Plus :size="13" aria-hidden="true" />新对话
      </button>
    </div>

    <div class="conv-list kb-scroll">
      <p v-if="loading && !items.length" class="conv-empty">正在加载…</p>
      <p v-else-if="!items.length" class="conv-empty">暂无会话</p>
      <button
        v-for="item in items"
        :key="item.id"
        type="button"
        class="conv-item"
        :class="{ active: String(item.id) === String(activeId) }"
        :disabled="disabled"
        @click="emit('select', item)"
      >
        <span class="conv-title">{{ item.title }}</span>
        <span class="conv-preview">{{ item.lastMessage || '尚未开始提问' }}</span>
        <span class="conv-actions">
          <button type="button" title="重命名" aria-label="重命名会话" @click.stop="emit('rename', item)">
            <Pencil :size="12" aria-hidden="true" />
          </button>
          <button type="button" class="danger" title="删除" aria-label="删除会话" @click.stop="emit('delete', item)">
            <Trash2 :size="12" aria-hidden="true" />
          </button>
        </span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.conversations {
  width: 224px;
  flex: none;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--kb-surface-2);
  border-right: 1px solid var(--kb-border);
}

.conv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 12px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--kb-text-secondary);
}

.new-conv {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 9px;
  color: var(--kb-primary-strong);
  background: var(--kb-primary-soft);
  border: 1px solid var(--kb-border-strong);
  border-radius: 999px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.new-conv:disabled,
.conv-item:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.conv-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 12px;
}

.conv-empty {
  padding: 10px 6px;
  font-size: 12px;
  color: var(--kb-text-muted);
}

.conv-item {
  position: relative;
  display: block;
  width: 100%;
  padding: 9px 10px;
  margin-bottom: 3px;
  overflow: hidden;
  text-align: left;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  font: inherit;
  cursor: pointer;
}

.conv-item:hover {
  background: var(--kb-surface);
  border-color: var(--kb-border);
}

.conv-item.active {
  background: var(--kb-primary-soft);
  border-color: var(--kb-primary);
}

.conv-title,
.conv-preview {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.conv-title {
  padding-right: 34px;
  font-size: 13px;
  font-weight: 550;
  color: var(--kb-text);
}

.conv-preview {
  margin-top: 3px;
  font-size: 11px;
  color: var(--kb-text-muted);
}

.conv-actions {
  position: absolute;
  top: 7px;
  right: 6px;
  display: none;
  gap: 4px;
}

.conv-item:hover .conv-actions,
.conv-item.active .conv-actions {
  display: flex;
}

.conv-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  color: var(--kb-text-secondary);
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: 6px;
  cursor: pointer;
}

.conv-actions button:hover {
  color: var(--kb-primary-strong);
  border-color: var(--kb-primary);
}
.conv-actions button.danger:hover {
  color: var(--kb-danger);
  border-color: var(--kb-danger);
}

@media (max-width: 900px) {
  .conversations {
    width: 188px;
  }
}

@media (max-width: 720px) {
  .conversations {
    display: none;
  }
}
</style>
