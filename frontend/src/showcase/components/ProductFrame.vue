<script setup lang="ts">
import {
  Activity,
  Bot,
  Check,
  ChevronDown,
  Circle,
  Database,
  FileCheck2,
  FileText,
  Gauge,
  KeyRound,
  MessageSquareText,
  MoreHorizontal,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  UploadCloud,
  Users,
} from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    variant?: 'governance' | 'documents' | 'qa' | 'admin'
    compact?: boolean
  }>(),
  {
    variant: 'qa',
    compact: false,
  },
)

const pageTitles = {
  governance: '成员与权限',
  documents: '知识库文档',
  qa: '智能问答',
  admin: '系统设置',
} as const

const sidebarItems = [
  { label: '工作台', icon: Gauge, variant: 'governance' },
  { label: '知识库', icon: Database, variant: 'documents' },
  { label: '智能问答', icon: MessageSquareText, variant: 'qa' },
  { label: '系统设置', icon: Settings2, variant: 'admin' },
] as const
</script>

<template>
  <div class="product-frame" :class="[`is-${props.variant}`, { 'is-compact': compact }]">
    <div class="window-bar">
      <span class="window-dots"><i /><i /><i /></span>
      <span class="window-url">knowbase.local / {{ pageTitles[props.variant] }}</span>
      <span class="window-mode">INTERFACE PREVIEW</span>
    </div>

    <div class="app-layout">
      <aside class="app-sidebar">
        <div class="mini-brand">
          <span class="mini-logo">K</span>
          <span>知库 AI<small>KNOWBASE</small></span>
        </div>

        <nav aria-label="产品导航预览">
          <span
            v-for="item in sidebarItems"
            :key="item.label"
            :class="{ active: item.variant === props.variant }"
          >
            <component :is="item.icon" :size="13" />
            {{ item.label }}
          </span>
        </nav>

        <div class="sidebar-block">
          <div class="sidebar-label"><span>知识库</span><span>+</span></div>
          <div class="kb-item active">
            <span class="kb-dot green" />
            <span>企业制度库</span>
            <small>12</small>
          </div>
          <div class="kb-item">
            <span class="kb-dot blue" />
            <span>研发规范库</span>
            <small>8</small>
          </div>
          <div class="kb-item">
            <span class="kb-dot amber" />
            <span>客服知识库</span>
            <small>5</small>
          </div>
        </div>

        <div class="sidebar-user">
          <span class="user-avatar">A</span>
          <span>管理员<small>系统平台</small></span>
          <MoreHorizontal :size="14" />
        </div>
      </aside>

      <section class="app-main">
        <header class="app-toolbar">
          <div>
            <span class="app-eyebrow">企业制度库 /</span>
            <strong>{{ pageTitles[props.variant] }}</strong>
          </div>
          <div class="toolbar-actions">
            <span class="health-chip"><Activity :size="11" /> 服务健康</span>
            <button type="button" aria-label="搜索预览"><Search :size="14" /></button>
          </div>
        </header>

        <div v-if="props.variant === 'qa'" class="preview-body qa-body">
          <div class="conversation-list">
            <div class="conversation-head">
              <span>最近对话</span>
              <button type="button">新建</button>
            </div>
            <div class="conversation-item active">
              <MessageSquareText :size="13" />
              <span>远程访问与数据分级</span>
            </div>
            <div class="conversation-item">
              <MessageSquareText :size="13" />
              <span>差旅报销时限</span>
            </div>
            <div class="conversation-item">
              <MessageSquareText :size="13" />
              <span>采购审批流程</span>
            </div>
          </div>

          <div class="chat-stage">
            <div class="chat-context">
              <span><FileCheck2 :size="12" /> 12 份文档已就绪</span>
              <span class="scope-chip"><ShieldCheck :size="11" /> 已授权范围</span>
            </div>
            <div class="question-bubble">
              远程访问机密数据有什么网络要求，误发数据后多久需要报告？
            </div>
            <div class="answer-block">
              <div class="answer-head">
                <span class="ai-mark"><Sparkles :size="13" /></span>
                <span>知识库回答</span>
                <span class="streaming-state"><Circle :size="6" fill="currentColor" /> 已生成</span>
              </div>
              <p>
                远程访问机密数据必须使用公司批准的安全网络通道，禁止通过公共网络直接传输。发现数据误发后，应在
                <strong>2 小时内</strong>报告信息安全团队并保留相关记录。
              </p>
              <div class="citation-row">
                <span><b>1</b> 远程办公规范 · 第 4 节</span>
                <span><b>2</b> 数据分级与传输规范 · 第 3.2 节</span>
              </div>
            </div>
            <div class="composer">
              <span>继续追问知识库...</span>
              <span class="composer-send"><Sparkles :size="13" /></span>
            </div>
          </div>
        </div>

        <div v-else-if="props.variant === 'documents'" class="preview-body documents-body">
          <div class="page-head">
            <div>
              <span class="app-eyebrow">知识库 / 企业制度库</span>
              <h3>文档处理</h3>
              <p>解析、分片与向量索引状态每 2 秒自动刷新。</p>
            </div>
            <button class="primary-mini" type="button"><UploadCloud :size="13" /> 上传文档</button>
          </div>
          <div class="doc-metrics">
            <div><span>可用文档</span><strong>12</strong><small>全部可检索</small></div>
            <div><span>处理中</span><strong>01</strong><small>异步任务</small></div>
            <div><span>失败任务</span><strong>00</strong><small>无积压</small></div>
          </div>
          <div class="doc-table">
            <div class="doc-table-head">
              <span>文件</span><span>状态</span><span>分片</span><span>尝试</span>
            </div>
            <div class="doc-row">
              <span class="doc-file"><FileText :size="15" /><span>远程办公规范.md<small>2026-10-30 · 8.4 KB</small></span></span>
              <span class="state success">可用</span>
              <span>6</span>
              <span>1/3</span>
            </div>
            <div class="doc-row">
              <span class="doc-file"><FileText :size="15" /><span>数据分级与传输规范.pdf<small>2026-10-30 · 1.2 MB</small></span></span>
              <span class="state success">可用</span>
              <span>18</span>
              <span>1/3</span>
            </div>
            <div class="doc-row">
              <span class="doc-file"><FileText :size="15" /><span>差旅与费用报销.md<small>2026-10-31 · 6.1 KB</small></span></span>
              <span class="state processing">处理中</span>
              <span>--</span>
              <span>1/3</span>
            </div>
          </div>
        </div>

        <div v-else-if="props.variant === 'governance'" class="preview-body governance-body">
          <div class="page-head">
            <div>
              <span class="app-eyebrow">知识库 / 成员与权限</span>
              <h3>访问成员</h3>
              <p>资源权限每次从数据库当前成员关系计算。</p>
            </div>
            <button class="primary-mini" type="button"><Users :size="13" /> 添加成员</button>
          </div>
          <div class="role-summary">
            <div><ShieldCheck :size="16" /><span>Owner<strong>完整管理</strong></span></div>
            <div><KeyRound :size="16" /><span>Manager<strong>成员与文档</strong></span></div>
            <div><FileText :size="16" /><span>Editor<strong>文档写入</strong></span></div>
            <div><Search :size="16" /><span>Viewer<strong>只读问答</strong></span></div>
          </div>
          <div class="member-list">
            <div class="member-row">
              <span class="user-avatar">L</span>
              <span class="member-name"><b>林澈</b><small>lin.chen</small></span>
              <span class="role-chip">MANAGER</span>
              <span class="state success">有效</span>
            </div>
            <div class="member-row">
              <span class="user-avatar blue">M</span>
              <span class="member-name"><b>孟楠</b><small>meng.nan</small></span>
              <span class="role-chip">EDITOR</span>
              <span class="state success">有效</span>
            </div>
            <div class="member-row">
              <span class="user-avatar amber">Z</span>
              <span class="member-name"><b>周序</b><small>zhou.xu</small></span>
              <span class="role-chip">VIEWER</span>
              <span class="state success">有效</span>
            </div>
          </div>
        </div>

        <div v-else class="preview-body admin-body">
          <div class="page-head">
            <div>
              <span class="app-eyebrow">管理员 / 系统设置</span>
              <h3>模型与运行状态</h3>
              <p>敏感配置通过服务端加密保存，密钥不回显。</p>
            </div>
            <span class="admin-badge"><ShieldCheck :size="12" /> SYS_ADMIN</span>
          </div>
          <div class="health-grid">
            <div><Database :size="14" /><span>MySQL<strong>UP · 8 ms</strong></span></div>
            <div><Activity :size="14" /><span>Redis<strong>UP · 3 ms</strong></span></div>
            <div><Bot :size="14" /><span>Qdrant<strong>UP · 11 ms</strong></span></div>
            <div><Sparkles :size="14" /><span>AI 服务<strong>UP · 24 ms</strong></span></div>
          </div>
          <div class="model-row">
            <span class="model-icon"><Bot :size="15" /></span>
            <span><strong>deepseek-chat</strong><small>openai-compatible · CHAT</small></span>
            <span class="masked-key">sk-••••••••4f2a</span>
            <span class="state success"><Check :size="11" /> 已连接</span>
            <ChevronDown :size="14" />
          </div>
          <div class="audit-strip">
            <span>最近审计</span>
            <span>MODEL_UPDATED</span>
            <span>trace · 9d2f••••</span>
            <span>2 分钟前</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.product-frame {
  position: relative;
  width: 100%;
  overflow: hidden;
  color: #1f2733;
  background: #eef1f5;
  border: 1px solid rgba(149, 174, 204, 0.2);
  border-radius: 9px;
  box-shadow:
    0 44px 120px rgba(0, 0, 0, 0.48),
    0 0 0 1px rgba(106, 148, 205, 0.08),
    0 0 80px rgba(50, 112, 210, 0.1);
}

.window-bar {
  height: 34px;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 0 14px;
  color: #718096;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.06em;
  background: #101722;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.window-dots {
  display: inline-flex;
  gap: 5px;
}

.window-dots i {
  width: 6px;
  height: 6px;
  background: #354154;
  border-radius: 50%;
}

.window-dots i:first-child {
  background: #ef6b62;
}

.window-dots i:nth-child(2) {
  background: #f0b74c;
}

.window-dots i:nth-child(3) {
  background: #49b87f;
}

.window-url {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.window-mode {
  justify-self: end;
  color: #7c8ca3;
  font-size: 7px;
  letter-spacing: 0.11em;
}

.app-layout {
  min-height: 510px;
  display: grid;
  grid-template-columns: 178px minmax(0, 1fr);
}

.app-sidebar {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 18px 12px 14px;
  color: #c3cbda;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.018), transparent 32%),
    #1f2430;
}

.mini-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 6px 18px;
  font-size: 11px;
  font-weight: 650;
}

.mini-brand > span:last-child {
  display: grid;
}

.mini-brand small {
  color: #788397;
  font-family: var(--font-mono);
  font-size: 6px;
  font-weight: 400;
  letter-spacing: 0.12em;
}

.mini-logo {
  width: 25px;
  height: 25px;
  display: grid;
  place-items: center;
  color: #0f1721;
  background: #6cc9ff;
  border-radius: 5px;
}

.app-sidebar nav {
  display: grid;
  gap: 2px;
}

.app-sidebar nav span {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 9px;
  color: #8d97aa;
  font-size: 9px;
  border-radius: 4px;
}

.app-sidebar nav span.active {
  color: #f3f7fc;
  background: #303948;
}

.sidebar-block {
  display: grid;
  gap: 3px;
  margin-top: 22px;
}

.sidebar-label {
  display: flex;
  justify-content: space-between;
  padding: 0 8px 7px;
  color: #687386;
  font-size: 7px;
  text-transform: uppercase;
  letter-spacing: 0.1em;
}

.kb-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 7px;
  padding: 7px 8px;
  color: #8f9aab;
  font-size: 8px;
  border-radius: 4px;
}

.kb-item.active {
  color: #dce5f1;
  background: rgba(255, 255, 255, 0.04);
}

.kb-item small {
  color: #707b8d;
  font-family: var(--font-mono);
  font-size: 6px;
}

.kb-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
}

.kb-dot.green {
  background: #57ce9b;
}

.kb-dot.blue {
  background: #659cf0;
}

.kb-dot.amber {
  background: #e6ac4b;
}

.sidebar-user {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding: 12px 6px 0;
  color: #bcc6d5;
  font-size: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.sidebar-user > span:nth-child(2) {
  display: grid;
}

.sidebar-user small {
  color: #697486;
  font-size: 6px;
}

.user-avatar {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  flex: none;
  color: #eaf4ff;
  font-size: 8px;
  font-weight: 650;
  background: #3c6cad;
  border-radius: 50%;
}

.user-avatar.blue {
  background: #316e6e;
}

.user-avatar.amber {
  background: #8a6432;
}

.app-main {
  min-width: 0;
  background: #f7f8fa;
}

.app-toolbar {
  height: 47px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 18px;
  background: #fff;
  border-bottom: 1px solid #e3e7ed;
}

.app-toolbar > div:first-child {
  display: flex;
  align-items: baseline;
  gap: 5px;
  font-size: 9px;
}

.app-eyebrow {
  color: #8a94a4;
  font-size: 7px;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.health-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 7px;
  color: #247558;
  font-size: 7px;
  background: #e9f8f1;
  border: 1px solid #caeadd;
  border-radius: 4px;
}

.toolbar-actions button {
  width: 25px;
  height: 25px;
  display: grid;
  place-items: center;
  color: #8d97a6;
  background: transparent;
  border: 1px solid #e1e5eb;
  border-radius: 4px;
}

.preview-body {
  padding: 18px;
}

.qa-body {
  min-height: 463px;
  display: grid;
  grid-template-columns: 155px minmax(0, 1fr);
  gap: 14px;
}

.conversation-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 11px 8px;
  background: #fff;
  border: 1px solid #e4e8ee;
  border-radius: 5px;
}

.conversation-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 8px;
  color: #8b95a4;
  font-size: 7px;
}

.conversation-head button {
  padding: 2px 5px;
  color: #326fd6;
  font-size: 7px;
  background: #edf3ff;
  border: 0;
  border-radius: 3px;
}

.conversation-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 7px;
  color: #7b8594;
  font-size: 8px;
  border-radius: 4px;
}

.conversation-item.active {
  color: #2c65c9;
  background: #edf3ff;
}

.conversation-item span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-stage {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 17px 20px 13px;
  background: #fff;
  border: 1px solid #e4e8ee;
  border-radius: 5px;
}

.chat-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #7c8797;
  font-size: 8px;
}

.chat-context span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.scope-chip {
  padding: 3px 6px;
  color: #247558 !important;
  background: #e9f8f1;
  border-radius: 3px;
}

.question-bubble {
  width: min(82%, 390px);
  align-self: flex-end;
  margin: 20px 0 17px;
  padding: 10px 12px;
  color: #28313e;
  font-size: 9px;
  line-height: 1.55;
  background: #edf3ff;
  border-radius: 7px 7px 2px 7px;
}

.answer-block {
  flex: 1;
}

.answer-head {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #3d4655;
  font-size: 9px;
  font-weight: 650;
}

.ai-mark {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  color: #2b70da;
  background: #e8f1ff;
  border-radius: 5px;
}

.streaming-state {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  color: #2b8b68;
  font-size: 7px;
  font-weight: 400;
}

.answer-block p {
  max-width: 90%;
  margin: 14px 0 0 29px;
  color: #46505e;
  font-size: 9px;
  line-height: 1.8;
}

.answer-block strong {
  color: #265fc5;
}

.citation-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 16px 0 0 29px;
}

.citation-row span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 7px;
  color: #4a617f;
  font-size: 7px;
  background: #f1f5fa;
  border: 1px solid #dfe6ef;
  border-radius: 4px;
}

.citation-row b {
  width: 13px;
  height: 13px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 6px;
  background: #3979df;
  border-radius: 50%;
}

.composer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18px;
  padding: 9px 10px;
  color: #9ba4b1;
  font-size: 8px;
  border: 1px solid #dfe4eb;
  border-radius: 5px;
}

.composer-send {
  width: 21px;
  height: 21px;
  display: grid;
  place-items: center;
  color: #fff;
  background: #3275dc;
  border-radius: 4px;
}

.documents-body,
.governance-body,
.admin-body {
  min-height: 463px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 16px;
}

.page-head h3 {
  margin: 3px 0 2px;
  color: #202936;
  font-size: 18px;
  line-height: 1.2;
}

.page-head p {
  margin: 0;
  color: #7f8998;
  font-size: 8px;
}

.primary-mini {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 9px;
  color: #fff;
  font-size: 8px;
  background: #2f6fed;
  border: 0;
  border-radius: 4px;
}

.doc-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin-bottom: 12px;
  background: #fff;
  border: 1px solid #e4e8ee;
  border-radius: 5px;
}

.doc-metrics div {
  display: grid;
  gap: 2px;
  padding: 12px 14px;
}

.doc-metrics div + div {
  border-left: 1px solid #e7eaf0;
}

.doc-metrics span,
.doc-metrics small {
  color: #8a94a2;
  font-size: 7px;
}

.doc-metrics strong {
  color: #27303c;
  font-size: 18px;
  line-height: 1.2;
}

.doc-table,
.member-list,
.model-row,
.audit-strip {
  overflow: hidden;
  background: #fff;
  border: 1px solid #e4e8ee;
  border-radius: 5px;
}

.doc-table-head,
.doc-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 82px 52px 52px;
  align-items: center;
  gap: 8px;
  padding: 10px 13px;
}

.doc-table-head {
  color: #8c96a4;
  font-size: 7px;
  background: #f8f9fb;
  border-bottom: 1px solid #e6e9ef;
}

.doc-row {
  color: #5c6675;
  font-size: 8px;
}

.doc-row + .doc-row {
  border-top: 1px solid #edf0f4;
}

.doc-file {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #344050;
}

.doc-file > span {
  min-width: 0;
  display: grid;
}

.doc-file small {
  color: #929ba7;
  font-size: 6px;
}

.state {
  width: max-content;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 6px;
  font-size: 7px;
  border-radius: 3px;
}

.state.success {
  color: #267557;
  background: #e9f8f1;
}

.state.processing {
  color: #946a1e;
  background: #fff5df;
}

.role-summary,
.health-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 12px;
  background: #fff;
  border: 1px solid #e4e8ee;
  border-radius: 5px;
}

.role-summary > div,
.health-grid > div {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 12px;
  color: #4d73a7;
}

.role-summary > div + div,
.health-grid > div + div {
  border-left: 1px solid #e8ebf0;
}

.role-summary span,
.health-grid span {
  min-width: 0;
  display: grid;
  color: #394351;
  font-size: 7px;
}

.role-summary strong,
.health-grid strong {
  color: #8993a1;
  font-size: 6px;
  font-weight: 500;
}

.member-row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 80px 62px;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
}

.member-row + .member-row {
  border-top: 1px solid #edf0f4;
}

.member-name {
  display: grid;
  color: #344050;
  font-size: 8px;
}

.member-name small {
  color: #929ba8;
  font-size: 6px;
}

.role-chip {
  width: max-content;
  padding: 3px 6px;
  color: #476383;
  font-family: var(--font-mono);
  font-size: 6px;
  background: #f0f4f8;
  border-radius: 3px;
}

.admin-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 7px;
  color: #2b5eaa;
  font-family: var(--font-mono);
  font-size: 7px;
  background: #edf3ff;
  border-radius: 4px;
}

.model-row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto auto auto;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
}

.model-icon {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  color: #386fc5;
  background: #eaf1fe;
  border-radius: 5px;
}

.model-row > span:nth-child(2) {
  display: grid;
  color: #344050;
  font-size: 8px;
}

.model-row small {
  color: #919aa7;
  font-size: 6px;
}

.masked-key {
  color: #738090;
  font-family: var(--font-mono);
  font-size: 7px;
}

.audit-strip {
  display: grid;
  grid-template-columns: 1fr auto auto auto;
  gap: 16px;
  margin-top: 10px;
  padding: 10px 14px;
  color: #7c8795;
  font-family: var(--font-mono);
  font-size: 6px;
}

.audit-strip span:first-child {
  color: #46515f;
  font-family: var(--font-body);
  font-size: 7px;
}

.is-compact .app-layout {
  min-height: 438px;
}

.is-compact .qa-body,
.is-compact .documents-body,
.is-compact .governance-body,
.is-compact .admin-body {
  min-height: 391px;
}

@media (max-width: 920px) {
  .window-url {
    max-width: 190px;
  }

  .app-layout {
    grid-template-columns: 148px minmax(0, 1fr);
  }

  .qa-body {
    grid-template-columns: 125px minmax(0, 1fr);
  }

  .preview-body {
    padding: 14px;
  }
}

@media (max-width: 700px) {
  .product-frame {
    min-height: 450px;
  }

  .window-bar {
    grid-template-columns: 1fr auto;
  }

  .window-url {
    display: none;
  }

  .app-layout {
    min-height: 416px;
    grid-template-columns: 1fr;
  }

  .app-sidebar {
    display: none;
  }

  .app-toolbar {
    padding: 0 13px;
  }

  .qa-body {
    grid-template-columns: 1fr;
  }

  .conversation-list {
    display: none;
  }

  .chat-stage {
    padding: 14px;
  }

  .question-bubble {
    width: 92%;
  }

  .answer-block p {
    max-width: 100%;
    margin-left: 0;
  }

  .citation-row {
    margin-left: 0;
  }

  .doc-table-head,
  .doc-row {
    grid-template-columns: minmax(0, 1fr) 70px;
  }

  .doc-table-head span:nth-child(n + 3),
  .doc-row > span:nth-child(n + 3) {
    display: none;
  }

  .role-summary,
  .health-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .role-summary > div:nth-child(3),
  .role-summary > div:nth-child(4),
  .health-grid > div:nth-child(3),
  .health-grid > div:nth-child(4) {
    border-top: 1px solid #e8ebf0;
  }

  .role-summary > div:nth-child(3),
  .health-grid > div:nth-child(3) {
    border-left: 0;
  }

  .member-row {
    grid-template-columns: 28px minmax(0, 1fr) auto;
  }

  .member-row .role-chip {
    display: none;
  }

  .model-row {
    grid-template-columns: 26px minmax(0, 1fr) auto;
  }

  .masked-key,
  .model-row > svg {
    display: none;
  }

  .audit-strip {
    grid-template-columns: 1fr auto;
  }

  .audit-strip span:nth-child(3) {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .product-frame * {
    transition: none !important;
  }
}
</style>
