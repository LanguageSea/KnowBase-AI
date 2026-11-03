<script setup lang="ts">
import {
  Check,
  DatabaseZap,
  MessageSquareText,
  Settings2,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-vue-next'
import { computed, ref } from 'vue'
import { capabilities } from '../data/content'
import ProductFrame from './ProductFrame.vue'
import SectionHeader from './SectionHeader.vue'

const activeId = ref(capabilities[0]?.id || 'governance')
const icons: Record<string, LucideIcon> = {
  governance: ShieldCheck,
  knowledge: DatabaseZap,
  rag: MessageSquareText,
  operations: Settings2,
}

const activeCapability = computed(
  () => capabilities.find((item) => item.id === activeId.value) || capabilities[0],
)
</script>

<template>
  <section id="capabilities" class="section section-capabilities section-anchor">
    <div class="section-inner">
      <SectionHeader
        eyebrow="03 / PRODUCT CAPABILITIES"
        title="从业务页面回到工程实现"
        intro="四组能力覆盖一个知识库系统最关键的工作流。界面预览对应现有业务前端，不使用虚构产品截图。"
      />

      <div class="capability-shell" data-reveal>
        <div class="capability-tabs" role="tablist" aria-label="核心能力">
          <button
            v-for="(capability, index) in capabilities"
            :key="capability.id"
            :id="`capability-tab-${capability.id}`"
            type="button"
            role="tab"
            :aria-selected="activeId === capability.id"
            :aria-controls="`capability-panel-${capability.id}`"
            :class="{ active: activeId === capability.id }"
            @click="activeId = capability.id"
          >
            <span class="tab-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="tab-icon">
              <component :is="icons[capability.id]" :size="17" :stroke-width="1.7" />
            </span>
            <span class="tab-copy">
              <strong>{{ capability.label }}</strong>
              <small>{{ capability.eyebrow }}</small>
            </span>
          </button>
        </div>

        <div class="capability-content" aria-live="polite">
          <div class="capability-info">
            <Transition name="capability" mode="out-in">
              <div :key="activeCapability?.id">
                <span class="content-kicker">{{ activeCapability?.eyebrow }}</span>
                <h3>{{ activeCapability?.title }}</h3>
                <p>{{ activeCapability?.summary }}</p>
                <ul>
                  <li v-for="point in activeCapability?.points" :key="point">
                    <Check :size="13" :stroke-width="2" />
                    <span>{{ point }}</span>
                  </li>
                </ul>
                <div class="capability-stack">
                  <span v-for="item in activeCapability?.stack" :key="item">{{ item }}</span>
                </div>
              </div>
            </Transition>
          </div>

          <div class="capability-visual">
            <Transition name="preview" mode="out-in">
              <ProductFrame
                :key="activeCapability?.id"
                :variant="activeCapability?.preview"
                compact
              />
            </Transition>
          </div>
        </div>
      </div>

      <div class="capability-strip" data-reveal>
        <span>登录认证</span>
        <i />
        <span>成员授权</span>
        <i />
        <span>文档解析</span>
        <i />
        <span>向量索引</span>
        <i />
        <span>流式问答</span>
        <i />
        <span>引用回验</span>
        <i />
        <span>审计与健康</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section-capabilities {
  background:
    linear-gradient(180deg, #071018, #0b1622 20%, #09131f);
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.capability-shell {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(8, 18, 29, 0.72);
}

.capability-tabs {
  display: grid;
  align-content: start;
  background: rgba(5, 13, 22, 0.5);
  border-right: 1px solid var(--line);
}

.capability-tabs button {
  min-width: 0;
  display: grid;
  grid-template-columns: 24px 32px minmax(0, 1fr);
  align-items: center;
  gap: 9px;
  padding: 18px 15px;
  color: var(--text-muted);
  text-align: left;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  transition:
    color 160ms ease,
    background-color 160ms ease;
}

.capability-tabs button:hover {
  color: var(--text-strong);
  background: rgba(255, 255, 255, 0.022);
}

.capability-tabs button.active {
  color: var(--text-strong);
  background:
    linear-gradient(90deg, rgba(72, 199, 217, 0.075), transparent),
    rgba(255, 255, 255, 0.025);
  box-shadow: inset 2px 0 var(--accent-cyan);
}

.tab-index {
  color: #485b70;
  font-family: var(--font-mono);
  font-size: 7px;
}

.tab-icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  color: #617891;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 5px;
}

.active .tab-icon {
  color: var(--accent-cyan);
  background: rgba(72, 199, 217, 0.07);
}

.tab-copy {
  min-width: 0;
  display: grid;
}

.tab-copy strong {
  font-size: 11px;
  font-weight: 560;
}

.tab-copy small {
  overflow: hidden;
  margin-top: 2px;
  color: #53657a;
  font-family: var(--font-mono);
  font-size: 6px;
  letter-spacing: 0.06em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capability-content {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(240px, 0.72fr) minmax(0, 1.28fr);
  gap: 25px;
  padding: 26px;
}

.capability-info {
  display: flex;
  align-items: center;
}

.content-kicker {
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.1em;
}

.capability-info h3 {
  max-width: 320px;
  margin: 11px 0 0;
  color: var(--text-strong);
  font-size: clamp(1.5rem, 2.3vw, 2.25rem);
  font-weight: 580;
  letter-spacing: -0.035em;
  line-height: 1.14;
}

.capability-info p {
  margin: 15px 0 0;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.75;
}

.capability-info ul {
  display: grid;
  gap: 9px;
  margin: 22px 0 0;
  padding: 0;
  list-style: none;
}

.capability-info li {
  display: grid;
  grid-template-columns: 17px minmax(0, 1fr);
  gap: 7px;
  color: #8999ac;
  font-size: 9px;
  line-height: 1.65;
}

.capability-info li svg {
  margin-top: 2px;
  color: var(--accent-green);
}

.capability-stack {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 22px;
}

.capability-stack span {
  padding: 4px 6px;
  color: #6f849a;
  font-family: var(--font-mono);
  font-size: 7px;
  border: 1px solid var(--line);
  border-radius: 3px;
}

.capability-visual {
  min-width: 0;
  align-self: center;
}

.capability-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  margin-top: 23px;
  overflow-x: auto;
  color: #6d7f94;
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.capability-strip i {
  width: 3px;
  height: 3px;
  flex: none;
  background: #34485d;
  border-radius: 50%;
}

.capability-enter-active,
.capability-leave-active,
.preview-enter-active,
.preview-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.capability-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.capability-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.preview-enter-from {
  opacity: 0;
  transform: translateX(10px);
}

.preview-leave-to {
  opacity: 0;
  transform: translateX(-10px);
}

@media (max-width: 1180px) {
  .capability-content {
    grid-template-columns: 1fr;
  }

  .capability-info p {
    max-width: 680px;
  }
}

@media (max-width: 850px) {
  .capability-shell {
    grid-template-columns: 1fr;
  }

  .capability-tabs {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }

  .capability-tabs button {
    grid-template-columns: 1fr;
    justify-items: center;
    padding: 13px 7px;
    text-align: center;
    border-right: 1px solid var(--line);
    border-bottom: 0;
  }

  .tab-index,
  .tab-copy small {
    display: none;
  }

  .capability-tabs button.active {
    box-shadow: inset 0 -2px var(--accent-cyan);
  }
}

@media (max-width: 700px) {
  .capability-content {
    padding: 16px;
    gap: 22px;
  }

  .capability-info h3 {
    font-size: 2rem;
  }

  .tab-copy strong {
    font-size: 9px;
  }

  .capability-strip {
    justify-content: flex-start;
  }
}
</style>
