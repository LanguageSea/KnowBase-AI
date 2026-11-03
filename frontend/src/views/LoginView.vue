<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BookOpenText, Database, MessagesSquare, ShieldCheck } from 'lucide-vue-next'
import { authApi } from '../api'
import { setCachedUser, setToken } from '../utils/auth'
import { notifyError } from '../utils/notify'

const router = useRouter()
const route = useRoute()

const formRef = ref(null)
const loading = ref(false)
const errorMsg = ref('')

const form = reactive({ username: 'admin', password: '' })
const isDev = import.meta.env.DEV
const testAccounts = [
  { username: 'admin', password: 'Admin@123456', label: '系统管理员' },
  { username: 'kbadmin', password: 'Test@123456', label: '知识库管理员' },
  { username: 'editor', password: 'Test@123456', label: '编辑用户' },
  { username: 'viewer', password: 'Test@123456', label: '只读用户' },
]
const highlights = [
  { icon: MessagesSquare, title: '可追溯问答', desc: '每次回答附引用来源，点击直达原文分片。' },
  { icon: ShieldCheck, title: '服务端权限过滤', desc: '检索前先鉴权，撤权即时生效。' },
  { icon: Database, title: '异步索引流水线', desc: '解析状态、失败原因、重试记录全程可见。' },
]
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

function fillTestAccount(account) {
  form.username = account.username
  form.password = account.password
  errorMsg.value = ''
}

async function onSubmit() {
  if (loading.value) return
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await authApi.login({ username: form.username.trim(), password: form.password })
    setToken(data.token)
    setCachedUser(data.user || null)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/knowledge-bases'
    await router.replace(redirect)
  } catch (err) {
    errorMsg.value = err.message || '登录失败，请稍后重试'
    notifyError(err)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-brand-panel">
      <div class="login-brand-head">
        <span class="login-mark"><BookOpenText :size="18" aria-hidden="true" /></span>
        <div>
          <strong>知库 AI</strong>
          <small>KNOWBASE AI · 企业知识库</small>
        </div>
      </div>
      <h1>把散落的文档，<br />变成可追溯的答案。</h1>
      <p class="login-lead">权限隔离、异步索引、引用可点击，一套为企业问答打磨的控制台。</p>
      <ul class="login-points">
        <li v-for="item in highlights" :key="item.title">
          <span class="point-icon"><component :is="item.icon" :size="16" aria-hidden="true" /></span>
          <div><strong>{{ item.title }}</strong><p>{{ item.desc }}</p></div>
        </li>
      </ul>
      <RouterLink class="login-showcase" to="/">先看看项目介绍 →</RouterLink>
    </section>
    <section class="login-form-panel">
      <div class="login-card">
        <h2>欢迎回来</h2>
        <p class="login-sub">使用管理员分配的账号登录控制台。</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="onSubmit">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" clearable />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" autocomplete="current-password" show-password @keyup.enter="onSubmit" />
          </el-form-item>
          <el-button type="primary" class="login-submit" native-type="submit" :loading="loading">
            {{ loading ? '正在登录…' : '登录' }}
          </el-button>
        </el-form>
        <p v-if="errorMsg" class="login-error">{{ errorMsg }}</p>
        <section v-if="isDev" class="test-accounts" aria-label="开发环境测试账号">
          <div class="test-accounts-head"><strong>测试账号</strong><span>点击自动填入</span></div>
          <div class="test-account-grid">
            <button v-for="account in testAccounts" :key="account.username" type="button" class="test-account" @click="fillTestAccount(account)">
              <span>{{ account.label }}</span><code>{{ account.username }}</code>
            </button>
          </div>
        </section>
        <p class="login-foot">同一账号仅保留最新登录设备，账号异常请联系管理员。</p>
      <RouterLink class="login-showcase-link" to="/">返回项目介绍</RouterLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page { min-height: 100dvh; display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr); background: var(--kb-bg); }
.login-brand-panel { position: relative; overflow: hidden; display: flex; flex-direction: column; justify-content: center; gap: 14px; padding: 64px; color: #eef1ff; background: radial-gradient(700px 420px at 15% 10%, rgba(14,165,233,0.35), transparent), radial-gradient(700px 520px at 90% 90%, rgba(79,70,229,0.5), transparent), linear-gradient(160deg, #111a33, #1c2560 60%, #2f3bb0); }
.login-brand-panel::after { content: ""; position: absolute; inset: 0; background: linear-gradient(transparent 60%, rgba(0,0,0,0.22)); pointer-events: none; }
.login-brand-head { display: flex; align-items: center; gap: 12px; }
.login-mark { display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; color: #1a2150; background: #fff; border-radius: 12px; }
.login-brand-head strong { display: block; font-size: 18px; }
.login-brand-head small { display: block; margin-top: 2px; font-size: 10px; letter-spacing: 1px; opacity: 0.75; }
.login-brand-panel h1 { margin-top: 18px; font-size: clamp(28px, 3.4vw, 42px); line-height: 1.25; font-weight: 750; letter-spacing: -0.5px; }
.login-lead { max-width: 420px; font-size: 14px; opacity: 0.85; }
.login-points { list-style: none; margin: 18px 0 0; padding: 0; display: grid; gap: 10px; max-width: 440px; }
.login-points li { display: flex; gap: 10px; padding: 12px 14px; background: rgba(255,255,255,0.1); border: 1px solid rgba(255,255,255,0.16); border-radius: 12px; backdrop-filter: blur(8px); }
.point-icon { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; flex: none; color: #1a2150; background: #fff; border-radius: 9px; }
.login-points strong { font-size: 13px; }
.login-points p { margin-top: 2px; font-size: 12px; opacity: 0.8; }
.login-showcase { margin-top: 16px; width: max-content; color: #fff; font-size: 13px; text-decoration: none; border-bottom: 1px solid rgba(255,255,255,0.5); }
.login-form-panel { display: flex; align-items: center; justify-content: center; padding: 48px 24px; }
.login-card { width: 400px; max-width: 100%; padding: 30px; background: var(--kb-surface); border: 1px solid var(--kb-border); border-radius: 16px; box-shadow: var(--kb-shadow-md); }
.login-card h2 { font-size: 22px; font-weight: 700; }
.login-sub { margin: 6px 0 18px; color: var(--kb-text-secondary); font-size: 13px; }
.login-submit { width: 100%; margin-top: 8px; min-height: 42px; }
.login-error { margin-top: 12px; padding: 8px 12px; font-size: 13px; color: var(--kb-danger); background: #fdf0ef; border: 1px solid #f6d5d2; border-radius: 8px; }
.login-foot { margin-top: 14px; font-size: 12px; color: var(--kb-text-muted); text-align: center; }
.login-showcase-link { display: block; margin-top: 8px; color: var(--kb-primary); font-size: 12px; text-align: center; text-decoration: none; }
.login-showcase-link:hover { text-decoration: underline; }
.test-accounts { padding: 12px; margin-top: 16px; background: var(--kb-surface-2); border: 1px solid var(--kb-border); border-radius: 10px; }
.test-accounts-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; font-size: 12px; }
.test-accounts-head span { color: var(--kb-text-muted); }
.test-account-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.test-account { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; padding: 7px 9px; color: var(--kb-text-secondary); background: var(--kb-surface); border: 1px solid var(--kb-border); border-radius: 6px; font-size: 11px; cursor: pointer; font-family: inherit; }
.test-account:hover { color: var(--kb-primary); border-color: var(--kb-primary); }
.test-account code { overflow: hidden; color: var(--kb-text-muted); font-family: inherit; text-overflow: ellipsis; }
@media (max-width: 900px) {
  .login-page { grid-template-columns: 1fr; }
  .login-brand-panel { padding: 40px 24px; min-height: 42dvh; }
  .login-form-panel { padding: 24px 16px 40px; margin-top: -24px; }
}
@media (prefers-reduced-motion: reduce) { .login-brand-panel { background: #1c2560; } }
</style>
