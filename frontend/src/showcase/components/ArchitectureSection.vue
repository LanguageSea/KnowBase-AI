<script setup lang="ts">
import {
  ArrowRight,
  Bot,
  Braces,
  Cloud,
  Database,
  HardDrive,
  Layers3,
  Monitor,
  Network,
  Server,
  ShieldCheck,
  Sparkles,
  Timer,
  type LucideIcon,
} from 'lucide-vue-next'
import { computed, ref } from 'vue'
import { architectureNodes, techGroups } from '../data/content'
import SectionHeader from './SectionHeader.vue'

const activeId = ref('java')
const iconMap: Record<string, LucideIcon> = {
  browser: Monitor,
  server: Server,
  braces: Braces,
  sparkles: Sparkles,
  database: Database,
  timer: Timer,
  network: Network,
  'hard-drive': HardDrive,
  cloud: Cloud,
}

const stages = [
  {
    id: 'edge',
    index: '01',
    label: 'Entry',
    title: '用户入口',
    nodes: architectureNodes.filter((node) => node.group === 'edge'),
  },
  {
    id: 'business',
    index: '02',
    label: 'Authority',
    title: '业务与授权',
    nodes: architectureNodes.filter((node) => node.group === 'business'),
  },
  {
    id: 'ai',
    index: '03',
    label: 'Compute',
    title: 'AI 计算',
    nodes: architectureNodes.filter((node) => node.group === 'ai'),
  },
  {
    id: 'data',
    index: '04',
    label: 'State',
    title: '数据与存储',
    nodes: architectureNodes.filter((node) => node.group === 'data'),
  },
]

const activeNode = computed(
  () => architectureNodes.find((node) => node.id === activeId.value) || architectureNodes[0],
)
</script>

<template>
  <section id="architecture" class="section section-architecture section-anchor">
    <div class="section-inner">
      <SectionHeader
        eyebrow="02 / SYSTEM ARCHITECTURE"
        title="一条链路，两个权威边界"
        intro="Java 决定用户能看什么，Python 只在已授权的知识库范围内完成解析、检索与生成。"
      />

      <div class="architecture-shell" data-reveal>
        <div class="flow-rail" aria-label="服务调用顺序">
          <span>HTTPS</span>
          <ArrowRight :size="12" />
          <span>JWT + AUTHZ</span>
          <ArrowRight :size="12" />
          <span>INTERNAL TOKEN</span>
          <ArrowRight :size="12" />
          <span>KB-SCOPED ACCESS</span>
        </div>

        <div class="architecture-grid">
          <div v-for="stage in stages" :key="stage.id" class="arch-stage">
            <div class="stage-head">
              <span>{{ stage.index }}</span>
              <div>
                <small>{{ stage.label }}</small>
                <strong>{{ stage.title }}</strong>
              </div>
            </div>

            <div class="node-list">
              <button
                v-for="node in stage.nodes"
                :key="node.id"
                type="button"
                class="arch-node"
                :class="{ active: activeId === node.id }"
                :aria-pressed="activeId === node.id"
                @click="activeId = node.id"
              >
                <span class="node-icon">
                  <component :is="iconMap[node.icon]" :size="17" :stroke-width="1.7" />
                </span>
                <span class="node-copy">
                  <strong>{{ node.label }}</strong>
                  <small>{{ node.short }}</small>
                </span>
                <span class="node-status" />
              </button>
            </div>
          </div>
        </div>

        <div class="node-detail" aria-live="polite">
          <div class="detail-title">
            <span class="detail-icon">
              <component
                :is="iconMap[activeNode.icon]"
                :size="23"
                :stroke-width="1.5"
              />
            </span>
            <div>
              <small>{{ activeNode.short }}</small>
              <h3>{{ activeNode.label }}</h3>
            </div>
          </div>
          <p class="detail-role">{{ activeNode.role }}</p>
          <div class="detail-columns">
            <div>
              <span>INPUT</span>
              <p>{{ activeNode.input }}</p>
            </div>
            <div>
              <span>OUTPUT</span>
              <p>{{ activeNode.output }}</p>
            </div>
            <div class="boundary-cell">
              <span><ShieldCheck :size="12" /> SECURITY BOUNDARY</span>
              <p>{{ activeNode.boundary }}</p>
            </div>
          </div>
        </div>
      </div>

      <div class="tech-matrix" data-reveal>
        <div class="matrix-head">
          <div>
            <Layers3 :size="17" />
            <span>技术栈与真实职责</span>
          </div>
          <small>只列项目实际使用项</small>
        </div>
        <div
          v-for="group in techGroups"
          :key="group.label"
          class="tech-row"
        >
          <div class="tech-label">
            <span :style="{ backgroundColor: group.accent }" />
            {{ group.label }}
          </div>
          <div class="tech-items">
            <div v-for="item in group.items" :key="item.name">
              <strong>{{ item.name }}</strong>
              <span>{{ item.role }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="architecture-note" data-reveal>
        <Bot :size="16" />
        <p>
          Python 服务不解析 JWT，不查询用户表，也不包含任何资源权限判断。
          浏览器传入的 <code>kbId</code> 只有经过 Java 实时鉴权后，才会成为 AI 检索范围。
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section-architecture {
  background:
    linear-gradient(180deg, #09131e 0%, #071018 18%, #071018 100%);
}

.architecture-shell {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(18, 37, 58, 0.52), rgba(9, 20, 32, 0.88)),
    #08121e;
}

.flow-rail {
  min-height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 9px 18px;
  color: #687c93;
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.08em;
  border-bottom: 1px solid var(--line);
}

.flow-rail svg {
  color: #3a4d62;
}

.architecture-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.arch-stage {
  min-width: 0;
  padding: 23px 18px;
}

.arch-stage + .arch-stage {
  border-left: 1px solid var(--line);
}

.stage-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 18px;
}

.stage-head > span {
  color: #52667d;
  font-family: var(--font-mono);
  font-size: 8px;
}

.stage-head div {
  display: grid;
}

.stage-head small {
  color: #5e7188;
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.stage-head strong {
  margin-top: 3px;
  color: #a6b6c9;
  font-size: 11px;
  font-weight: 550;
}

.node-list {
  display: grid;
  gap: 7px;
}

.arch-node {
  width: 100%;
  min-width: 0;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px;
  color: var(--text-secondary);
  text-align: left;
  background: rgba(255, 255, 255, 0.022);
  border: 1px solid rgba(130, 157, 189, 0.12);
  border-radius: 5px;
  cursor: pointer;
  transition:
    border-color 150ms ease,
    background-color 150ms ease,
    transform 150ms ease;
}

.arch-node:hover {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(130, 157, 189, 0.24);
}

.arch-node.active {
  background: rgba(72, 199, 217, 0.055);
  border-color: rgba(72, 199, 217, 0.32);
  transform: translateX(2px);
}

.node-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  color: #758aa3;
  background: rgba(126, 154, 187, 0.06);
  border-radius: 4px;
}

.arch-node.active .node-icon {
  color: var(--accent-cyan);
  background: rgba(72, 199, 217, 0.09);
}

.node-copy {
  min-width: 0;
  display: grid;
}

.node-copy strong {
  overflow: hidden;
  color: #dce6f2;
  font-size: 10px;
  font-weight: 560;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-copy small {
  color: #64758a;
  font-size: 8px;
}

.node-status {
  width: 4px;
  height: 4px;
  background: #46586d;
  border-radius: 50%;
}

.arch-node.active .node-status {
  background: var(--accent-cyan);
  box-shadow: 0 0 9px rgba(72, 199, 217, 0.55);
}

.node-detail {
  display: grid;
  grid-template-columns: 240px 1fr 1.4fr;
  gap: 28px;
  align-items: center;
  padding: 24px 26px;
  background: rgba(4, 11, 18, 0.5);
  border-top: 1px solid var(--line);
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 13px;
}

.detail-icon {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  color: var(--accent-cyan);
  background: rgba(72, 199, 217, 0.06);
  border: 1px solid rgba(72, 199, 217, 0.13);
  border-radius: 6px;
}

.detail-title small,
.detail-columns span {
  color: #5d7086;
  font-family: var(--font-mono);
  font-size: 7px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.detail-title h3 {
  margin: 2px 0 0;
  color: var(--text-strong);
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 520;
}

.detail-role {
  margin: 0;
  color: #9aabba;
  font-size: 11px;
  line-height: 1.75;
}

.detail-columns {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.detail-columns div {
  min-width: 0;
}

.detail-columns span {
  display: flex;
  align-items: center;
  gap: 5px;
}

.detail-columns p {
  margin: 6px 0 0;
  color: #7e8fa3;
  font-size: 9px;
  line-height: 1.65;
}

.boundary-cell {
  padding-left: 18px;
  border-left: 1px solid var(--line);
}

.boundary-cell span {
  color: var(--accent-green);
}

.tech-matrix {
  margin-top: 40px;
  border-top: 1px solid var(--line);
}

.matrix-head {
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--line);
}

.matrix-head div {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: #b9c6d4;
  font-size: 11px;
}

.matrix-head svg {
  color: var(--accent-blue);
}

.matrix-head small {
  color: #5c6d81;
  font-family: var(--font-mono);
  font-size: 8px;
}

.tech-row {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr);
  gap: 30px;
  padding: 17px 0;
  border-bottom: 1px solid var(--line);
}

.tech-label {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #8698ad;
  font-size: 10px;
}

.tech-label > span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
}

.tech-items {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.tech-items div {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.tech-items strong {
  color: #c8d5e3;
  font-size: 10px;
  font-weight: 550;
}

.tech-items span {
  color: #617185;
  font-size: 8px;
  line-height: 1.55;
}

.architecture-note {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 12px;
  align-items: start;
  margin-top: 28px;
  padding: 16px 18px;
  color: var(--accent-cyan);
  background: rgba(72, 199, 217, 0.035);
  border: 1px solid rgba(72, 199, 217, 0.1);
  border-radius: 6px;
}

.architecture-note p {
  margin: 0;
  color: #8293a6;
  font-size: 10px;
  line-height: 1.75;
}

.architecture-note code {
  padding: 1px 4px;
  color: #b9cbe0;
  font-family: var(--font-mono);
  background: rgba(255, 255, 255, 0.05);
  border-radius: 3px;
}

@media (max-width: 1050px) {
  .architecture-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .arch-stage:nth-child(3) {
    border-left: 0;
    border-top: 1px solid var(--line);
  }

  .arch-stage:nth-child(4) {
    border-top: 1px solid var(--line);
  }

  .node-detail {
    grid-template-columns: 220px 1fr;
  }

  .detail-columns {
    grid-column: 1 / -1;
  }

  .tech-items {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .flow-rail {
    justify-content: flex-start;
    overflow-x: auto;
    white-space: nowrap;
  }

  .architecture-grid {
    grid-template-columns: 1fr;
  }

  .arch-stage + .arch-stage {
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .node-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .node-detail {
    grid-template-columns: 1fr;
    gap: 18px;
  }

  .detail-columns {
    grid-template-columns: 1fr;
  }

  .boundary-cell {
    padding: 14px 0 0;
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .tech-row {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .matrix-head {
    align-items: flex-start;
    flex-direction: column;
    justify-content: center;
    padding: 12px 0;
  }
}

@media (max-width: 460px) {
  .node-list {
    grid-template-columns: 1fr;
  }

  .tech-items {
    grid-template-columns: 1fr;
  }
}
</style>
