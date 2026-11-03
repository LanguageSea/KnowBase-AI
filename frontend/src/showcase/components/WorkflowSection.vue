<script setup lang="ts">
import { Check, Clipboard, Copy, FileCode2, Terminal } from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflows } from '../data/content'
import SectionHeader from './SectionHeader.vue'

const activeId = ref(workflows[0]?.id || 'ingestion')
const copied = ref(false)
let copyTimer: ReturnType<typeof setTimeout> | undefined

const activeWorkflow = computed(
  () => workflows.find((workflow) => workflow.id === activeId.value) || workflows[0],
)

async function copyCode() {
  if (!activeWorkflow.value) return
  try {
    await navigator.clipboard.writeText(activeWorkflow.value.code)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = activeWorkflow.value.code
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  copied.value = true
  window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => {
    copied.value = false
  }, 1800)
}

watch(activeId, () => {
  copied.value = false
})

onBeforeUnmount(() => {
  window.clearTimeout(copyTimer)
})
</script>

<template>
  <section id="engineering" class="section section-engineering section-anchor">
    <div class="section-inner">
      <SectionHeader
        eyebrow="04 / ENGINEERING NOTES"
        title="关键路径，逐层拆开"
        intro="五个技术专题都来自现有实现。每个专题包含设计目标、执行顺序和可核验代码。"
      />

      <div class="workflow-tabs" role="tablist" aria-label="技术专题">
        <button
          v-for="(workflow, index) in workflows"
          :key="workflow.id"
          :id="`workflow-tab-${workflow.id}`"
          type="button"
          role="tab"
          :aria-selected="activeId === workflow.id"
          :aria-controls="`workflow-panel-${workflow.id}`"
          :class="{ active: activeId === workflow.id }"
          @click="activeId = workflow.id"
        >
          <span>{{ String(index + 1).padStart(2, '0') }}</span>
          {{ workflow.label }}
        </button>
      </div>

      <div
        :id="`workflow-panel-${activeWorkflow?.id}`"
        class="workflow-panel"
        role="tabpanel"
        :aria-labelledby="`workflow-tab-${activeWorkflow?.id}`"
        data-reveal
      >
        <Transition name="workflow-copy" mode="out-in">
          <div :key="`copy-${activeWorkflow?.id}`" class="workflow-copy">
            <span class="workflow-eyebrow">{{ activeWorkflow?.eyebrow }}</span>
            <h3>{{ activeWorkflow?.title }}</h3>
            <p class="workflow-summary">{{ activeWorkflow?.summary }}</p>

            <ol class="workflow-steps">
              <li v-for="(step, index) in activeWorkflow?.steps" :key="step.title">
                <span class="step-number">{{ String(index + 1).padStart(2, '0') }}</span>
                <div>
                  <strong>{{ step.title }}</strong>
                  <p>{{ step.detail }}</p>
                </div>
              </li>
            </ol>
          </div>
        </Transition>

        <Transition name="workflow-code" mode="out-in">
          <div :key="`code-${activeWorkflow?.id}`" class="code-window">
            <div class="code-head">
              <div>
                <FileCode2 :size="14" />
                <span>{{ activeWorkflow?.codeLabel }}</span>
              </div>
              <button type="button" :class="{ copied }" @click="copyCode">
                <Check v-if="copied" :size="13" />
                <Copy v-else :size="13" />
                {{ copied ? '已复制' : '复制代码' }}
              </button>
            </div>
            <div class="code-body">
              <div class="line-numbers" aria-hidden="true">
                <span v-for="line in activeWorkflow?.code.split('\n').length" :key="line">
                  {{ line }}
                </span>
              </div>
              <pre><code>{{ activeWorkflow?.code }}</code></pre>
            </div>
            <div class="code-foot">
              <span><Terminal :size="11" /> READ-ONLY SNIPPET</span>
              <span>关键逻辑节选，完整实现见源码</span>
            </div>
          </div>
        </Transition>
      </div>

      <div class="engineering-aside" data-reveal>
        <Clipboard :size="15" />
        <p>
          代码片段用于说明关键边界，不替代完整实现。任务状态、异常分支和并发保护仍需结合源码与测试查看。
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section-engineering {
  background: #f1f3f6;
  color: #202a36;
}

.section-engineering :deep(.section-header h2) {
  color: #17212c;
}

.section-engineering :deep(.section-header p) {
  color: #5f6c7b;
}

.section-engineering :deep(.eyebrow-index) {
  color: #24708b;
}

.section-engineering :deep(.eyebrow-copy),
.section-engineering :deep(.eyebrow-line) {
  color: #7c8897;
  background-color: #c9d0d8;
}

.workflow-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 24px;
  padding: 4px;
  background: #e5e9ee;
  border: 1px solid #d9dfe6;
  border-radius: 7px;
}

.workflow-tabs button {
  flex: 1;
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 9px 12px;
  color: #6a7684;
  font-size: 10px;
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  transition:
    color 160ms ease,
    background-color 160ms ease,
    box-shadow 160ms ease;
}

.workflow-tabs button span {
  color: #95a0ad;
  font-family: var(--font-mono);
  font-size: 7px;
}

.workflow-tabs button:hover {
  color: #2c3947;
}

.workflow-tabs button.active {
  color: #15212e;
  background: #fff;
  box-shadow: 0 2px 7px rgba(28, 42, 58, 0.08);
}

.workflow-tabs button.active span {
  color: #257491;
}

.workflow-panel {
  display: grid;
  grid-template-columns: minmax(0, 0.8fr) minmax(440px, 1.2fr);
  overflow: hidden;
  background: #fff;
  border: 1px solid #d9dfe6;
  border-radius: 8px;
  box-shadow: 0 22px 60px rgba(31, 47, 66, 0.07);
}

.workflow-copy {
  padding: clamp(28px, 4vw, 48px);
}

.workflow-eyebrow {
  color: #3383a0;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.09em;
}

.workflow-copy h3 {
  max-width: 480px;
  margin: 15px 0 0;
  color: #162330;
  font-size: clamp(1.8rem, 3.2vw, 3rem);
  font-weight: 600;
  letter-spacing: -0.045em;
  line-height: 1.08;
}

.workflow-summary {
  max-width: 540px;
  margin: 18px 0 0;
  color: #657281;
  font-size: 11px;
  line-height: 1.8;
}

.workflow-steps {
  display: grid;
  gap: 0;
  margin: 30px 0 0;
  padding: 0;
  list-style: none;
}

.workflow-steps li {
  position: relative;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 12px;
  padding-bottom: 22px;
}

.workflow-steps li:last-child {
  padding-bottom: 0;
}

.workflow-steps li:not(:last-child)::after {
  position: absolute;
  top: 23px;
  bottom: 2px;
  left: 11px;
  width: 1px;
  background: #dbe3eb;
  content: '';
}

.step-number {
  position: relative;
  z-index: 1;
  width: 23px;
  height: 23px;
  display: grid;
  place-items: center;
  color: #33748b;
  font-family: var(--font-mono);
  font-size: 7px;
  background: #eef7fa;
  border: 1px solid #d8e9ef;
  border-radius: 50%;
}

.workflow-steps strong {
  display: block;
  color: #2b3745;
  font-size: 10px;
  font-weight: 600;
}

.workflow-steps p {
  margin: 3px 0 0;
  color: #7b8795;
  font-size: 9px;
  line-height: 1.65;
}

.code-window {
  min-width: 0;
  display: flex;
  flex-direction: column;
  margin: 24px 24px 24px 0;
  overflow: hidden;
  background: #0b121b;
  border: 1px solid #172332;
  border-radius: 7px;
  box-shadow: 0 18px 42px rgba(8, 14, 21, 0.2);
}

.code-head {
  min-height: 46px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  padding: 8px 13px 8px 16px;
  color: #8292a6;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.code-head > div {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 8px;
}

.code-head svg {
  color: #58a9c1;
}

.code-head button {
  min-height: 27px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 8px;
  color: #8799ad;
  font-size: 8px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 4px;
  cursor: pointer;
}

.code-head button:hover,
.code-head button.copied {
  color: #71dbbc;
}

.code-body {
  flex: 1;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  min-height: 360px;
  padding: 22px 0;
  overflow: auto;
}

.line-numbers {
  display: grid;
  align-content: start;
  padding-right: 12px;
  color: #35475b;
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 1.85;
  text-align: right;
  border-right: 1px solid rgba(255, 255, 255, 0.055);
}

pre {
  min-width: 0;
  margin: 0;
  padding: 0 20px;
  overflow-x: auto;
}

code {
  color: #b7c7d9;
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1.85;
  white-space: pre;
}

.code-foot {
  min-height: 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  padding: 7px 14px;
  color: #526478;
  font-family: var(--font-mono);
  font-size: 6px;
  letter-spacing: 0.04em;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.code-foot span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.engineering-aside {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 10px;
  margin-top: 20px;
  padding: 14px 16px;
  color: #3a7588;
  background: rgba(54, 125, 148, 0.05);
  border: 1px solid #d4e2e7;
  border-radius: 6px;
}

.engineering-aside p {
  margin: 0;
  color: #687785;
  font-size: 9px;
  line-height: 1.7;
}

.workflow-copy-enter-active,
.workflow-copy-leave-active,
.workflow-code-enter-active,
.workflow-code-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.workflow-copy-enter-from {
  opacity: 0;
  transform: translateY(9px);
}

.workflow-copy-leave-to {
  opacity: 0;
  transform: translateY(-9px);
}

.workflow-code-enter-from {
  opacity: 0;
  transform: translateX(10px);
}

.workflow-code-leave-to {
  opacity: 0;
  transform: translateX(-10px);
}

@media (max-width: 1000px) {
  .workflow-tabs {
    overflow-x: auto;
  }

  .workflow-tabs button {
    flex: none;
    min-width: 120px;
  }

  .workflow-panel {
    grid-template-columns: 1fr;
  }

  .code-window {
    margin: 0 24px 24px;
  }
}

@media (max-width: 640px) {
  .workflow-tabs button {
    min-width: 102px;
    padding-inline: 8px;
  }

  .workflow-copy {
    padding: 26px 21px;
  }

  .code-window {
    margin: 0 12px 12px;
  }

  .code-body {
    min-height: 300px;
  }

  .code-foot span:last-child {
    display: none;
  }
}
</style>
