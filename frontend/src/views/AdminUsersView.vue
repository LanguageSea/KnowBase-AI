<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { getCachedUser } from '../utils/auth'
import { notifyError, notifySuccess } from '../utils/notify'
import MainLayout from '../layouts/MainLayout.vue'
import AdminModelsPanel from '../components/AdminModelsPanel.vue'
import AdminAuditPanel from '../components/AdminAuditPanel.vue'

const route = useRoute()
const currentUser = getCachedUser()
const loading = ref(false)
const users = ref([])
const keyword = ref('')
const statusFilter = ref('ALL')
const statusBusy = ref(null)
const tabNames = ['users', 'models', 'audit']
const activeTab = ref(tabNames.includes(String(route.query.tab)) ? String(route.query.tab) : 'users')

const ROLE_OPTIONS = [
  { value: 'SYS_ADMIN', label: '系统管理员' },
  { value: 'KB_ADMIN', label: '知识库管理员' },
  { value: 'USER', label: '普通用户' },
]

const roleMeta = {
  SYS_ADMIN: { label: '系统管理员', type: 'danger' },
  KB_ADMIN: { label: '知识库管理员', type: 'warning' },
  USER: { label: '普通用户', type: 'info' },
}

const summary = computed(() => ({
  total: users.value.length,
  active: users.value.filter((user) => user.status === 'ACTIVE').length,
  disabled: users.value.filter((user) => user.status === 'DISABLED').length,
  admins: users.value.filter((user) => user.roles?.includes('SYS_ADMIN')).length,
}))

const filteredUsers = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return users.value.filter((user) => {
    if (statusFilter.value !== 'ALL' && user.status !== statusFilter.value) return false
    if (!query) return true
    return [user.username, user.nickname, user.email]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(query))
  })
})

function roleLabel(role) {
  return roleMeta[role]?.label || role
}

function roleType(role) {
  return roleMeta[role]?.type || 'info'
}

function formatDate(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false })
}

async function loadUsers() {
  loading.value = true
  try {
    const data = await adminApi.listUsers()
    users.value = data?.items || []
  } catch (err) {
    notifyError(err)
  } finally {
    loading.value = false
  }
}

const createVisible = ref(false)
const createSaving = ref(false)
const createFormRef = ref(null)
const createForm = reactive({
  username: '',
  password: '',
  nickname: '',
  email: '',
  roles: ['USER'],
})
const createRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_.-]{3,64}$/,
      message: '仅允许字母、数字、点、下划线和短横线，长度 3-64',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 128, message: '密码长度必须为 8-128 个字符', trigger: 'blur' },
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 64, message: '昵称不能超过 64 个字符', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  roles: [{ type: 'array', required: true, message: '至少分配一个角色', trigger: 'change' }],
}

function openCreate() {
  Object.assign(createForm, {
    username: '',
    password: '',
    nickname: '',
    email: '',
    roles: ['USER'],
  })
  createFormRef.value?.clearValidate()
  createVisible.value = true
}

async function submitCreate() {
  if (!createFormRef.value || createSaving.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  createSaving.value = true
  try {
    await adminApi.createUser({
      username: createForm.username.trim(),
      password: createForm.password,
      nickname: createForm.nickname.trim(),
      email: createForm.email.trim() || null,
      roles: createForm.roles,
    })
    notifySuccess('用户已创建')
    createVisible.value = false
    await loadUsers()
  } catch (err) {
    notifyError(err)
  } finally {
    createSaving.value = false
  }
}

const editVisible = ref(false)
const editSaving = ref(false)
const editFormRef = ref(null)
const editingUser = ref(null)
const editForm = reactive({ nickname: '', email: '' })
const editRules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 64, message: '昵称不能超过 64 个字符', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

function openEdit(user) {
  editingUser.value = user
  Object.assign(editForm, { nickname: user.nickname || '', email: user.email || '' })
  editVisible.value = true
}

async function submitEdit() {
  if (!editFormRef.value || editSaving.value) return
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) return
  editSaving.value = true
  try {
    await adminApi.updateUser(editingUser.value.id, {
      nickname: editForm.nickname.trim(),
      email: editForm.email.trim() || null,
    })
    notifySuccess('用户资料已更新')
    editVisible.value = false
    await loadUsers()
  } catch (err) {
    notifyError(err)
  } finally {
    editSaving.value = false
  }
}

const rolesVisible = ref(false)
const rolesSaving = ref(false)
const rolesUser = ref(null)
const rolesForm = reactive({ roles: [] })

function openRoles(user) {
  rolesUser.value = user
  rolesForm.roles = [...(user.roles || [])]
  rolesVisible.value = true
}

async function submitRoles() {
  if (!rolesForm.roles.length || rolesSaving.value) return
  rolesSaving.value = true
  try {
    await adminApi.replaceRoles(rolesUser.value.id, rolesForm.roles)
    notifySuccess('角色已更新')
    rolesVisible.value = false
    await loadUsers()
  } catch (err) {
    notifyError(err)
  } finally {
    rolesSaving.value = false
  }
}

const passwordVisible = ref(false)
const passwordSaving = ref(false)
const passwordFormRef = ref(null)
const passwordUser = ref(null)
const passwordForm = reactive({ password: '', confirmPassword: '' })
const passwordRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 128, message: '密码长度必须为 8-128 个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

function openPassword(user) {
  passwordUser.value = user
  passwordForm.password = ''
  passwordForm.confirmPassword = ''
  passwordVisible.value = true
}

async function submitPassword() {
  if (!passwordFormRef.value || passwordSaving.value) return
  const valid = await passwordFormRef.value.validate().catch(() => false)
  if (!valid) return
  passwordSaving.value = true
  try {
    await adminApi.resetPassword(passwordUser.value.id, passwordForm.password)
    notifySuccess('密码已重置，旧会话已失效')
    passwordVisible.value = false
  } catch (err) {
    notifyError(err)
  } finally {
    passwordSaving.value = false
  }
}

async function toggleStatus(user) {
  if (statusBusy.value) return
  const nextStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (nextStatus === 'DISABLED') {
    try {
      await ElMessageBox.confirm(
        `禁用「${user.nickname || user.username}」后，该用户所有登录会话会立即失效。`,
        '禁用用户',
        {
          type: 'warning',
          confirmButtonText: '禁用',
          cancelButtonText: '取消',
        },
      )
    } catch (err) {
      return
    }
  }
  statusBusy.value = user.id
  try {
    await adminApi.updateStatus(user.id, nextStatus)
    notifySuccess(nextStatus === 'ACTIVE' ? '用户已启用' : '用户已禁用')
    await loadUsers()
  } catch (err) {
    notifyError(err)
  } finally {
    statusBusy.value = null
  }
}

onMounted(loadUsers)
</script>

<template>
  <MainLayout eyebrow="系统管理" title="系统设置" subtitle="统一管理账号角色、模型服务、运行健康与审计记录。">
    <template #actions>
      <el-button v-if="activeTab === 'users'" type="primary" @click="openCreate">
        新建用户
      </el-button>
    </template>
    <div class="admin-body">

      <el-tabs v-model="activeTab" class="admin-tabs">
        <el-tab-pane label="用户与角色" name="users">
          <section class="summary">
            <div>
              <span>全部用户</span>
              <strong>{{ summary.total }}</strong>
            </div>
            <div>
              <span>启用</span>
              <strong>{{ summary.active }}</strong>
            </div>
            <div>
              <span>禁用</span>
              <strong>{{ summary.disabled }}</strong>
            </div>
            <div>
              <span>系统管理员</span>
              <strong>{{ summary.admins }}</strong>
            </div>
          </section>

          <section class="table-panel">
            <div class="toolbar">
              <div class="filters">
                <el-input
                  v-model="keyword"
                  clearable
                  placeholder="搜索用户名、昵称或邮箱"
                  class="search-input"
                />
                <el-select v-model="statusFilter" class="status-select">
                  <el-option label="全部状态" value="ALL" />
                  <el-option label="启用" value="ACTIVE" />
                  <el-option label="禁用" value="DISABLED" />
                </el-select>
              </div>
              <el-button text :loading="loading" @click="loadUsers">刷新</el-button>
            </div>

            <el-table
              v-loading="loading"
              :data="filteredUsers"
              row-key="id"
              empty-text="没有符合条件的用户"
            >
              <el-table-column label="用户" min-width="210">
                <template #default="{ row }">
                  <div class="user-name">{{ row.nickname }}</div>
                  <div class="user-meta">{{ row.username }} · ID {{ row.id }}</div>
                </template>
              </el-table-column>
              <el-table-column label="角色" min-width="230">
                <template #default="{ row }">
                  <div class="role-list">
                    <el-tag
                      v-for="role in row.roles"
                      :key="role"
                      size="small"
                      :type="roleType(role)"
                      effect="plain"
                    >
                      {{ roleLabel(role) }}
                    </el-tag>
                    <el-button link type="primary" @click="openRoles(row)">配置</el-button>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="125">
                <template #default="{ row }">
                  <div class="status-cell">
                    <el-switch
                      :model-value="row.status === 'ACTIVE'"
                      :loading="statusBusy === row.id"
                      :disabled="row.id === currentUser?.id"
                      size="small"
                      @change="toggleStatus(row)"
                    />
                    <span>{{ row.status === 'ACTIVE' ? '启用' : '禁用' }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="邮箱" min-width="190">
                <template #default="{ row }">{{ row.email || '-' }}</template>
              </el-table-column>
              <el-table-column label="最近登录" width="180">
                <template #default="{ row }">{{ formatDate(row.lastLoginAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="190" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                  <el-button link type="primary" @click="openPassword(row)">重置密码</el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </el-tab-pane>

        <el-tab-pane label="模型服务" name="models" lazy>
          <AdminModelsPanel v-if="activeTab === 'models'" />
        </el-tab-pane>

        <el-tab-pane label="审计与健康" name="audit" lazy>
          <AdminAuditPanel v-if="activeTab === 'audit'" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog
      v-model="createVisible"
      title="新建用户"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
      >
        <div class="form-grid">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="createForm.username" maxlength="64" />
          </el-form-item>
          <el-form-item label="初始密码" prop="password">
            <el-input v-model="createForm.password" type="password" show-password />
          </el-form-item>
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="createForm.nickname" maxlength="64" />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="createForm.email" maxlength="128" />
          </el-form-item>
        </div>
        <el-form-item label="系统角色" prop="roles">
          <el-checkbox-group v-model="createForm.roles">
            <el-checkbox v-for="role in ROLE_OPTIONS" :key="role.value" :value="role.value">
              {{ role.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="createSaving" @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSaving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑用户资料" width="480px">
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-position="top"
      >
        <el-form-item label="用户名">
          <el-input :model-value="editingUser?.username" disabled />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="editForm.nickname" maxlength="64" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="editSaving" @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rolesVisible" title="分配系统角色" width="460px">
      <p class="dialog-context">{{ rolesUser?.nickname }} · {{ rolesUser?.username }}</p>
      <el-checkbox-group v-model="rolesForm.roles" class="role-options">
        <el-checkbox v-for="role in ROLE_OPTIONS" :key="role.value" :value="role.value">
          {{ role.label }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button :disabled="rolesSaving" @click="rolesVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!rolesForm.roles.length"
          :loading="rolesSaving"
          @click="submitRoles"
        >
          保存角色
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordVisible" title="重置密码" width="460px">
      <p class="dialog-context">{{ passwordUser?.nickname }} · {{ passwordUser?.username }}</p>
      <el-form
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-position="top"
      >
        <el-form-item label="新密码" prop="password">
          <el-input v-model="passwordForm.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="passwordSaving" @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="submitPassword">
          重置密码
        </el-button>
      </template>
    </el-dialog>
  </MainLayout>
</template>

<style scoped>
.admin-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.admin-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

.admin-body { display: grid; gap: 12px; }

.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 16px;
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
}

.summary > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
}

.summary > div + div {
  border-left: 1px solid var(--kb-border);
}

.summary span {
  color: var(--kb-text-muted);
  font-size: 12px;
}

.summary strong {
  font-size: 21px;
  font-weight: 650;
}

.table-panel {
  background: var(--kb-surface);
  border: 1px solid var(--kb-border);
  border-radius: var(--kb-radius);
  overflow: hidden;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 56px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--kb-border);
}

.filters {
  display: flex;
  gap: 8px;
}

.search-input {
  width: 280px;
}

.status-select {
  width: 120px;
}

.user-name {
  font-weight: 600;
}

.user-meta {
  margin-top: 2px;
  color: var(--kb-text-muted);
  font-size: 11px;
}

.role-list,
.status-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.status-cell span {
  color: var(--kb-text-secondary);
  font-size: 12px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.dialog-context {
  padding: 9px 11px;
  margin-bottom: 14px;
  color: var(--kb-text-secondary);
  background: #f8f9fb;
  border-radius: 4px;
  font-size: 12px;
}

.role-options {
  display: grid;
  gap: 8px;
}

@media (max-width: 760px) {
  .content {
    width: calc(100% - 20px);
    padding-top: 20px;
  }

  .page-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .summary > div:nth-child(3) {
    border-left: 0;
    border-top: 1px solid var(--kb-border);
  }

  .summary > div:nth-child(4) {
    border-top: 1px solid var(--kb-border);
  }

  .toolbar,
  .filters {
    align-items: stretch;
    flex-direction: column;
  }

  .search-input,
  .status-select {
    width: 100%;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
