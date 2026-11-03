<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { BookOpenText, Copy, Globe2, RotateCcw } from 'lucide-vue-next'
import { qaApi } from '../api'
import { notifyError, notifySuccess, notifyWarning } from '../utils/notify'
import CitationCard from './CitationCard.vue'
import ConversationList from './ConversationList.vue'
import MarkdownText from './common/MarkdownText.vue'

const props = defineProps({
  kbCount: { type: Number, default: 0 },
  docCount: { type: Number, default: 0 },
})

const router = useRouter()
const question = ref('')
const sending = ref(false)
const messages = ref([])
const conversations = ref([])
const conversationLoading = ref(false)
const activeConversationId = ref(null)
const activeConversationTitle = ref('')
const activeCitation = ref({ key: null, rank: null })
const scrollEl = ref(null)
const abortController = ref(null)
const typingStates = new Map()
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
let seq = 0

const canSend = computed(
  () => !sending.value && question.value.trim().length > 0,
)
const localFallback = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    const message = messages.value[index]
    if (message.role === 'assistant' && message.data?.chatProvider?.startsWith('local-')) {
      return true
    }
  }
  return false
})
const chatHeading = computed(
  () => activeConversationTitle.value || '全局知识问答',
)

onMounted(loadConversations)

onBeforeUnmount(() => {
  stop(false)
  clearAllTyping()
})

function getTypingState(message) {
  let state = typingStates.get(message.key)
  if (!state) {
    state = { buffer: '', queue: '', timer: null, pendingCompletion: null }
    typingStates.set(message.key, state)
  }
  return state
}

function applyTypingCompletion(message, state) {
  if (!state.pendingCompletion) return
  Object.assign(message.data, state.pendingCompletion)
  state.pendingCompletion = null
  typingStates.delete(message.key)
}

function scheduleTyping(message, state) {
  if (state.timer || !state.queue) {
    if (!state.queue) applyTypingCompletion(message, state)
    return
  }
  state.timer = window.setTimeout(() => {
    state.timer = null
    const size =
      state.queue.length > 320 ? 8 : state.queue.length > 120 ? 4 : state.queue.length > 36 ? 2 : 1
    message.data.answer += state.queue.slice(0, size)
    state.queue = state.queue.slice(size)
    scrollToBottom()
    if (state.queue) {
      scheduleTyping(message, state)
    } else {
      applyTypingCompletion(message, state)
    }
  }, 24)
}

function bufferAnswerText(message, text) {
  const value = String(text || '')
  if (!value) return
  const state = getTypingState(message)
  state.buffer += value
}

function startTyping(message, text) {
  const state = getTypingState(message)
  if (state.timer) window.clearTimeout(state.timer)
  state.timer = null
  state.buffer = ''
  state.queue = String(text || '')
  message.data.answer = ''
  if (reducedMotion.matches) {
    message.data.answer = state.queue
    state.queue = ''
    applyTypingCompletion(message, state)
    return
  }
  scheduleTyping(message, state)
}

function flushTyping(message) {
  const state = typingStates.get(message.key)
  if (!state) return
  if (state.timer) window.clearTimeout(state.timer)
  state.timer = null
  message.data.answer += state.queue || state.buffer
  state.buffer = ''
  state.queue = ''
  applyTypingCompletion(message, state)
}

function clearAllTyping() {
  for (const state of typingStates.values()) {
    if (state.timer) window.clearTimeout(state.timer)
  }
  typingStates.clear()
}

function parseAnswer(text) {
  const raw = typeof text === 'string' ? text : ''
  const parts = []
  const pattern = /\[(\d+)]/g
  let last = 0
  let match = pattern.exec(raw)
  while (match) {
    if (match.index > last) parts.push({ type: 'text', text: raw.slice(last, match.index) })
    parts.push({ type: 'ref', rank: Number(match[1]) })
    last = match.index + match[0].length
    match = pattern.exec(raw)
  }
  if (last < raw.length) parts.push({ type: 'text', text: raw.slice(last) })
  return parts
}

function hasRank(message, rank) {
  return (message.data?.citations || []).some((citation) => Number(citation.rank) === Number(rank))
}

function citeId(key, rank) {
  return `cite-${key}-${rank}`
}

function isActive(key, rank) {
  return activeCitation.value.key === key && Number(activeCitation.value.rank) === Number(rank)
}

function focusCitation(key, rank) {
  if (isActive(key, rank)) {
    activeCitation.value = { key: null, rank: null }
    return
  }
  activeCitation.value = { key, rank }
  nextTick(() => {
    document.getElementById(citeId(key, rank))?.scrollIntoView({
      block: 'nearest',
      behavior: 'smooth',
    })
  })
}

function scrollToBottom() {
  nextTick(() => {
    const element = scrollEl.value
    if (element) element.scrollTop = element.scrollHeight
  })
}

async function loadConversations() {
  conversationLoading.value = true
  try {
    const data = await qaApi.conversations(null, { page: 1, size: 50 })
    conversations.value = data?.items || []
  } catch (error) {
    notifyError(error)
  } finally {
    conversationLoading.value = false
  }
}

async function openConversation(item) {
  if (sending.value) return
  try {
    clearAllTyping()
    const data = await qaApi.conversation(item.id, { page: 1, size: 100 })
    activeConversationId.value = data.id
    activeConversationTitle.value = data.title
    activeCitation.value = { key: null, rank: null }
    messages.value = data.messages.map((message) => mapStoredMessage(message, data.id))
    scrollToBottom()
  } catch (error) {
    notifyError(error)
  }
}

function mapStoredMessage(message, conversationId) {
  if (message.role === 'USER') {
    return { key: `stored-${message.id}`, role: 'user', text: message.content }
  }
  return {
    key: `stored-${message.id}`,
    role: 'assistant',
    data: {
      conversationId,
      messageId: message.id,
      answer: message.content,
      noAnswer: message.content === '未找到相关依据',
      citations: message.citations || [],
      chatProvider: message.chatProvider || '',
      latencyMs: message.latencyMs,
      status: message.status,
      errorCode: message.errorCode,
    },
  }
}

function startNewConversation() {
  if (sending.value) return
  clearAllTyping()
  messages.value = []
  activeConversationId.value = null
  activeConversationTitle.value = ''
  activeCitation.value = { key: null, rank: null }
  question.value = ''
}

async function renameConversation(item) {
  if (sending.value) return
  try {
    const { value } = await ElMessageBox.prompt('输入新的会话标题', '重命名会话', {
      inputValue: item.title,
      inputValidator: (input) => !!String(input || '').trim() || '标题不能为空',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    await qaApi.renameConversation(item.id, value.trim())
    if (Number(activeConversationId.value) === Number(item.id)) {
      activeConversationTitle.value = value.trim()
    }
    notifySuccess('会话已重命名')
    await loadConversations()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error)
  }
}

async function deleteConversation(item) {
  if (sending.value) return
  try {
    await ElMessageBox.confirm(`删除会话「${item.title}」及全部消息？`, '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await qaApi.deleteConversation(item.id)
    if (Number(activeConversationId.value) === Number(item.id)) startNewConversation()
    notifySuccess('会话已删除')
    await loadConversations()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error)
  }
}

async function send() {
  if (sending.value) return
  const text = question.value.trim()
  if (!text) {
    notifyWarning('请输入问题')
    return
  }
  if (text.length > 2000) {
    notifyWarning('问题不能超过 2000 字')
    return
  }

  const userKey = ++seq
  const assistantKey = ++seq
  messages.value.push({ key: userKey, role: 'user', text })
  const assistant = reactive({
    key: assistantKey,
    role: 'assistant',
    data: {
      answer: '',
      noAnswer: false,
      citations: [],
      chatProvider: '',
      status: 'STREAMING',
      conversationId: activeConversationId.value,
    },
  })
  messages.value.push(assistant)
  question.value = ''
  sending.value = true
  activeCitation.value = { key: null, rank: null }
  scrollToBottom()

  const controller = new AbortController()
  abortController.value = controller
  try {
    await qaApi.stream(
      {
        conversationId: activeConversationId.value,
        question: text,
      },
      {
        signal: controller.signal,
        onEvent(event, data) {
          if (event === 'meta') {
            activeConversationId.value = data.conversationId
            assistant.data.conversationId = data.conversationId
            assistant.data.messageId = data.messageId
            if (!activeConversationTitle.value) activeConversationTitle.value = data.title
          } else if (event === 'retrieval') {
            assistant.data.retrievalCount = data.count
            assistant.data.retrievalMode = data.mode || ''
          } else if (event === 'delta') {
            bufferAnswerText(assistant, data.text || '')
          } else if (event === 'no_answer') {
            bufferAnswerText(assistant, data.answer || '未找到相关依据')
            assistant.data.noAnswer = true
          } else if (event === 'done') {
            const state = getTypingState(assistant)
            const finalAnswer = data.answer || state.buffer
            assistant.data.chatProvider = data.chatProvider || ''
            assistant.data.embeddingProvider = data.embeddingProvider || ''
            assistant.data.latencyMs = data.latencyMs
            assistant.data.status = 'TYPING'
            state.pendingCompletion = {
              noAnswer: !!data.noAnswer,
              citations: data.citations || [],
              chatProvider: data.chatProvider || '',
              embeddingProvider: data.embeddingProvider || '',
              latencyMs: data.latencyMs,
              status: data.status || 'OK',
            }
            startTyping(assistant, finalAnswer)
          } else if (event === 'cancelled') {
            const state = getTypingState(assistant)
            const partialAnswer = data.answer || state.buffer
            if (partialAnswer) {
              startTyping(assistant, partialAnswer)
            }
            flushTyping(assistant)
            assistant.data.status = 'CANCELLED'
          }
        },
      },
    )
  } catch (error) {
    if (error.name === 'AbortError') {
      flushTyping(assistant)
      assistant.data.status = 'CANCELLED'
    } else {
      flushTyping(assistant)
      assistant.data.status = 'FAILED'
      assistant.data.errorCode = error.code
      assistant.data.errorText = error.message || '提问失败，请稍后重试'
      notifyError(error)
    }
  } finally {
    sending.value = false
    abortController.value = null
    await loadConversations()
    scrollToBottom()
  }
}

function stop(notify = true) {
  if (!sending.value || !abortController.value) return
  abortController.value.abort()
  if (notify) notifySuccess('已停止生成')
}

async function openCitation(citation) {
  if (citation.accessible === false) {
    notifyWarning('当前账号已无权查看该引用')
    return
  }
  try {
    const source = citation.id
      ? await qaApi.citation(citation.id)
      : {
          kbId: citation.kbId,
          docId: citation.docId,
          seq: citation.seq,
        }
    if (!source.kbId) {
      notifyWarning('引用缺少知识库信息，请刷新后重试')
      return
    }
    await router.push({
      name: 'document-detail',
      params: { kbId: source.kbId, docId: source.docId },
      query: { seq: source.seq },
    })
  } catch (error) {
    notifyError(error)
  }
}

function onKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  send()
}

function copyAnswer(message) {
  const text = String(message.data?.answer || '')
  if (!text) return
  const done = () => notifySuccess('已复制回答')
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(() => fallbackCopy(text, done))
  } else {
    fallbackCopy(text, done)
  }
}

function fallbackCopy(text, done) {
  try {
    const area = window.document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.opacity = '0'
    window.document.body.appendChild(area)
    area.select()
    window.document.execCommand('copy')
    window.document.body.removeChild(area)
    done()
  } catch (e) {
    notifyError(e)
  }
}

function resend(message) {
  if (sending.value) return
  const index = messages.value.findIndex((item) => item.key === message.key)
  const prev = index > 0 ? messages.value[index - 1] : null
  if (prev?.role === 'user' && prev.text) {
    question.value = prev.text
    send()
  }
}
</script>

<template>
  <section class="chat-shell">
    <ConversationList
      :items="conversations"
      :active-id="activeConversationId"
      :loading="conversationLoading"
      :disabled="sending"
      @new="startNewConversation"
      @select="openConversation"
      @rename="renameConversation"
      @delete="deleteConversation"
    />

    <section class="chat">
      <header class="chat-head">
        <div class="chat-head-main">
          <div class="chat-title-row">
            <Globe2 :size="17" aria-hidden="true" />
            <h1 class="chat-title">{{ chatHeading }}</h1>
          </div>
          <span class="chat-meta">
            <BookOpenText :size="13" aria-hidden="true" />
            {{ kbCount }} 个知识库 · {{ docCount }} 篇文档
          </span>
        </div>
        <button
          v-if="sending"
          type="button"
          class="stop-button"
          @click="stop()"
        >
          停止生成
        </button>
      </header>

      <div ref="scrollEl" class="chat-body kb-scroll">
        <div v-if="!messages.length" class="placeholder">
          <div class="placeholder-icon" aria-hidden="true">
            <Globe2 :size="24" />
          </div>
          <p class="placeholder-title">直接问，AI 自动检索全部知识库</p>
          <p class="placeholder-desc">
            命中资料时综合分析并附引用；没有命中时先说明，再基于通用知识回答。
          </p>
        </div>

        <div v-else class="msg-list">
          <div
            v-for="message in messages"
            :key="message.key"
            class="msg"
            :class="`msg-${message.role}`"
          >
            <template v-if="message.role === 'user'">
              <div class="bubble-user">{{ message.text }}</div>
            </template>

            <template v-else>
              <div class="answer-card">
                <div
                  v-if="['FAILED', 'EMPTY_COMPLETION'].includes(message.data?.status)"
                  class="answer-error"
                >
                  <strong>生成失败</strong>
                  <p>
                    {{
                      message.data.status === 'EMPTY_COMPLETION'
                        ? '模型未返回有效内容，请稍后重试'
                        : message.data.errorText || message.data.errorCode || '请稍后重试'
                    }}
                  </p>
                </div>

                <div v-else-if="message.data?.noAnswer" class="no-answer">
                  <span class="no-answer-tag">提示</span>
                  <p class="no-answer-text">{{ message.data.answer || '未找到相关依据' }}</p>
                </div>

                <template v-else>
                  <div class="answer-text">
                    <span
                      v-if="!message.data?.answer && message.data?.status === 'STREAMING'"
                      class="generating-label"
                    >
                      正在生成完整回答
                    </span>
                    <template v-else>
                      <template
                        v-for="(part, index) in parseAnswer(message.data?.answer)"
                        :key="index"
                      >
                        <button
                          v-if="part.type === 'ref' && hasRank(message, part.rank)"
                          type="button"
                          class="cite-ref"
                          :class="{ on: isActive(message.key, part.rank) }"
                          :title="`查看引用 [${part.rank}]`"
                          @click="focusCitation(message.key, part.rank)"
                        >
                          {{ part.rank }}
                        </button><MarkdownText
                          v-else-if="part.type === 'text'"
                          class="md-inline"
                          :text="part.text"
                        /><span v-else>{{ `[${part.rank}]` }}</span>
                      </template>
                      <span
                        v-if="
                          message.data?.answer &&
                          ['STREAMING', 'TYPING'].includes(message.data?.status)
                        "
                        class="stream-caret"
                      />
                    </template>
                  </div>

                  <div
                    v-if="message.data?.citations?.length"
                    class="citations"
                  >
                    <div class="citations-head">
                      引用来源
                      <span class="citations-count">{{ message.data.citations.length }}</span>
                    </div>
                    <CitationCard
                      v-for="citation in message.data.citations"
                      :id="citeId(message.key, citation.rank)"
                      :key="`${message.key}-${citation.rank}`"
                      :citation="citation"
                      :active="isActive(message.key, citation.rank)"
                      @open="openCitation(citation)"
                    />
                  </div>

                  <div class="answer-meta">
                    <span
                      v-if="message.data?.retrievalMode === 'general_fallback'"
                      class="answer-mode"
                    >
                      通用知识
                    </span>
                    <span
                      v-else-if="message.data?.retrievalCount > 0"
                      class="answer-mode grounded"
                    >
                      {{ message.data.retrievalCount }} 条依据
                    </span>
                    <span>{{ message.data?.chatProvider || '正在生成' }}</span>
                    <template v-if="message.data?.latencyMs != null">
                      <span class="dot">·</span>
                      <span>{{ message.data.latencyMs }} ms</span>
                    </template>
                    <template v-if="message.data?.status === 'CANCELLED'">
                      <span class="dot">·</span>
                      <span>已停止</span>
                    </template>
                    <template v-else-if="message.data?.status === 'TYPING'">
                      <span class="dot">·</span>
                      <span>正在输出</span>
                    </template>
                    <span class="answer-actions">
                      <button
                        type="button"
                        class="answer-action"
                        :disabled="!message.data?.answer || sending"
                        title="复制回答"
                        aria-label="复制回答"
                        @click="copyAnswer(message)"
                      >
                        <Copy :size="13" aria-hidden="true" />复制
                      </button>
                      <button
                        type="button"
                        class="answer-action"
                        :disabled="sending"
                        title="重新生成"
                        aria-label="重新生成"
                        @click="resend(message)"
                      >
                        <RotateCcw :size="13" aria-hidden="true" />重新生成
                      </button>
                    </span>
                  </div>
                </template>
              </div>
            </template>
          </div>
        </div>
      </div>

      <div v-if="localFallback" class="provider-hint">
        当前为本地回退模式；引用可追溯，语义质量不代表真实模型
      </div>

      <div class="composer">
        <el-input
          v-model="question"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="2000"
          show-word-limit
          :disabled="sending"
          placeholder="输入问题，AI 会检索所有你有权限访问的知识库…"
          @keydown="onKeydown"
        />
        <div class="composer-bar">
          <span class="composer-tip">Enter 发送，Shift + Enter 换行</span>
          <el-button
            v-if="!sending"
            type="primary"
            :disabled="!canSend"
            @click="send"
          >
            发送
          </el-button>
          <el-button v-else type="danger" plain @click="stop()">停止</el-button>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.chat-shell {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  overflow: hidden;
  background: var(--kb-bg);
}

.chat {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 12px 24px;
  background: var(--kb-surface);
  border-bottom: 1px solid var(--kb-border);
}

.chat-head-main {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}

.chat-title-row {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: var(--kb-primary);
}

.chat-title {
  max-width: 520px;
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  color: var(--kb-text);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.chat-meta {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--kb-text-muted);
  font-size: 12px;
}

.stop-button {
  flex: none;
  padding: 5px 10px;
  color: #9b3d35;
  background: #fff7f5;
  border: 1px solid #efcec9;
  border-radius: 4px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.chat-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px;
}

.placeholder {
  max-width: 480px;
  margin: 72px auto 0;
  text-align: center;
}

.placeholder-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  margin-bottom: 14px;
  color: var(--kb-primary);
  background: var(--kb-primary-soft);
  border: 1px solid #d6e4fd;
  border-radius: 50%;
}

.placeholder-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--kb-text-secondary);
}

.placeholder-desc {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--kb-text-muted);
}

.msg-list {
  max-width: 900px;
  margin: 0 auto;
}

.msg + .msg {
  margin-top: 16px;
}

.msg-user {
  display: flex;
  justify-content: flex-end;
}

.bubble-user {
  max-width: 78%;
  padding: 8px 12px;
  color: #16345f;
  background: var(--kb-primary-soft);
  border: 1px solid #d6e4fd;
  border-radius: var(--kb-radius);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.answer-card {
  padding: 16px;
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: 12px;
  box-shadow: var(--kb-shadow-sm);
}

.answer-text {
  color: var(--kb-text);
  font-size: 14px;
  line-height: 1.75;
  word-break: break-word;
}

.md-inline { display: inline; }

.generating-label {
  color: var(--kb-text-muted);
}

.stream-caret {
  display: inline-block;
  width: 2px;
  height: 1.1em;
  margin-left: 3px;
  vertical-align: text-bottom;
  background: var(--kb-primary);
  animation: blink 0.9s step-end infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.cite-ref {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  margin: 0 2px;
  color: var(--kb-primary);
  vertical-align: super;
  background: var(--kb-primary-soft);
  border: 1px solid #c9dafb;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
}

.cite-ref.on {
  color: #ffffff;
  background: var(--kb-primary);
  border-color: var(--kb-primary);
}

.citations {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--kb-border);
}

.citations-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  color: var(--kb-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.citations-count {
  padding: 0 6px;
  color: var(--kb-text-muted);
  background: #eef1f6;
  border-radius: 3px;
  font-weight: 400;
}

.answer-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 12px;
  color: var(--kb-text-muted);
  font-size: 12px;
}

.answer-actions { display: inline-flex; align-items: center; gap: 6px; margin-left: auto; }
.answer-action {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 8px; font: inherit; font-size: 12px;
  color: var(--kb-text-secondary); background: transparent;
  border: 1px solid var(--kb-border); border-radius: 999px; cursor: pointer;
  transition: color 0.14s, border-color 0.14s, background 0.14s;
}
.answer-action:hover:not(:disabled) { color: var(--kb-primary-strong); border-color: var(--kb-primary); background: var(--kb-primary-soft); }
.answer-action:disabled { opacity: 0.45; cursor: not-allowed; }

.answer-mode {
  padding: 1px 6px;
  color: #7d5d18;
  background: #fff5dd;
  border-radius: 3px;
}

.answer-mode.grounded {
  color: #246844;
  background: #eaf8ef;
}

.dot {
  color: #c2c8d4;
}

.no-answer {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 12px;
  background: #fffaf0;
  border: 1px solid #f2e2bf;
  border-radius: var(--kb-radius);
}

.no-answer-tag {
  flex: none;
  margin-top: 2px;
  padding: 0 6px;
  color: #96660d;
  background: #f8e7c4;
  border-radius: 3px;
  font-size: 11px;
  line-height: 18px;
}

.no-answer-text {
  color: #5b4a28;
  font-size: 14px;
  line-height: 1.8;
}

.answer-error {
  padding: 10px 12px;
  color: #7a3b36;
  background: #fdf4f3;
  border: 1px solid #f0cfcc;
  border-radius: var(--kb-radius);
}

.answer-error strong {
  font-size: 13px;
}

.answer-error p {
  margin-top: 3px;
  font-size: 13px;
  white-space: pre-wrap;
}

.provider-hint {
  flex: none;
  margin: 0 24px;
  padding: 6px 12px;
  color: #6b6f7b;
  background: #f1f3f7;
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
  font-size: 12px;
  line-height: 1.6;
}

.composer {
  flex: none;
  padding: 12px 24px 16px;
  background: var(--kb-surface);
  border-top: 1px solid var(--kb-border);
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 8px;
}

.composer-tip {
  color: var(--kb-text-muted);
  font-size: 12px;
}

:deep(.el-textarea__inner) {
  font-size: 14px;
  line-height: 1.7;
}

@media (max-width: 900px) {
  .chat-meta {
    display: none;
  }

  .chat-body,
  .composer {
    padding-right: 16px;
    padding-left: 16px;
  }
}
</style>
