import http from './http'
import { clearSession, getToken } from '../utils/auth'

export const authApi = {
  login(payload) {
    // 登录失败属于预期分支，由页面内联展示，不触发 401 跳转
    return http.post('/auth/login', payload, { skipAuthRedirect: true })
  },
  me() {
    return http.get('/auth/me')
  },
  logout() {
    return http.post('/auth/logout', null, { skipAuthRedirect: true })
  },
}

export const adminApi = {
  listUsers() {
    return http.get('/admin/users')
  },
  getUser(userId) {
    return http.get(`/admin/users/${userId}`)
  },
  createUser(payload) {
    return http.post('/admin/users', payload)
  },
  updateUser(userId, payload) {
    return http.put(`/admin/users/${userId}`, payload)
  },
  updateStatus(userId, status) {
    return http.patch(`/admin/users/${userId}/status`, { status })
  },
  replaceRoles(userId, roles) {
    return http.put(`/admin/users/${userId}/roles`, { roles })
  },
  resetPassword(userId, newPassword) {
    return http.put(`/admin/users/${userId}/password`, { newPassword })
  },
  listModels() {
    return http.get('/admin/models')
  },
  createModel(payload) {
    return http.post('/admin/models', payload)
  },
  updateModel(modelId, payload) {
    return http.put(`/admin/models/${modelId}`, payload)
  },
  deleteModel(modelId) {
    return http.delete(`/admin/models/${modelId}`)
  },
  testModel(modelId) {
    return http.post(`/admin/models/${modelId}/test`, null, { timeout: 30000 })
  },
  modelStats(days = 7) {
    return http.get('/admin/models/stats', { params: { days } })
  },
  auditLogs(params = {}) {
    return http.get('/admin/audit-logs', { params })
  },
  systemStatus() {
    return http.get('/admin/system/status')
  },
}

export const workspaceApi = {
  summary() {
    return http.get('/workspace/summary')
  },
}

export const kbApi = {
  list() {
    return http.get('/knowledge-bases')
  },
  create(payload) {
    return http.post('/knowledge-bases', payload)
  },
  get(kbId) {
    return http.get(`/knowledge-bases/${kbId}`)
  },
  members(kbId) {
    return http.get(`/knowledge-bases/${kbId}/members`)
  },
  memberCandidates(kbId, keyword = '') {
    return http.get(`/knowledge-bases/${kbId}/members/candidates`, { params: { keyword } })
  },
  grantMember(kbId, payload) {
    return http.post(`/knowledge-bases/${kbId}/members`, payload)
  },
  updateMember(kbId, userId, role) {
    return http.put(`/knowledge-bases/${kbId}/members/${userId}`, { role })
  },
  revokeMember(kbId, userId) {
    return http.delete(`/knowledge-bases/${kbId}/members/${userId}`)
  },
  documents(kbId) {
    return http.get(`/knowledge-bases/${kbId}/documents`)
  },
  document(kbId, docId) {
    return http.get(`/knowledge-bases/${kbId}/documents/${docId}`)
  },
  chunks(kbId, docId, params = {}) {
    return http.get(`/knowledge-bases/${kbId}/documents/${docId}/chunks`, { params })
  },
  deleteDocument(kbId, docId) {
    return http.delete(`/knowledge-bases/${kbId}/documents/${docId}`)
  },
  retryDocument(kbId, docId) {
    return http.post(`/knowledge-bases/${kbId}/documents/${docId}/retry`)
  },
  downloadDocument(kbId, docId) {
    return http.get(`/knowledge-bases/${kbId}/documents/${docId}/download`, {
      responseType: 'blob',
      timeout: 120000,
    })
  },
  upload(kbId, file) {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/knowledge-bases/${kbId}/documents`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    })
  },
}

export const qaApi = {
  ask(payload) {
    return http.post('/qa/ask', payload, { timeout: 120000 })
  },
  conversations(kbId, params = {}) {
    return http.get('/qa/conversations', { params: { kbId, ...params } })
  },
  createConversation(payload) {
    return http.post('/qa/conversations', payload)
  },
  conversation(conversationId, params = {}) {
    return http.get(`/qa/conversations/${conversationId}`, { params })
  },
  renameConversation(conversationId, title) {
    return http.patch(`/qa/conversations/${conversationId}`, { title })
  },
  deleteConversation(conversationId) {
    return http.delete(`/qa/conversations/${conversationId}`)
  },
  citation(citationId) {
    return http.get(`/qa/citations/${citationId}`)
  },
  async stream(payload, { signal, onEvent } = {}) {
    const response = await fetch('/qa/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
      },
      body: JSON.stringify(payload),
      signal,
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      if (response.status === 401) clearSession()
      const error = new Error((body && body.message) || `请求失败（HTTP ${response.status}）`)
      error.code = body && body.code
      error.traceId = (body && body.traceId) || response.headers.get('X-Trace-Id') || ''
      error.httpStatus = response.status
      throw error
    }
    if (!response.body) throw new Error('浏览器不支持流式响应')

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    const dispatch = (block) => {
      let event = 'message'
      const dataLines = []
      for (const rawLine of block.split(/\r?\n/)) {
        if (rawLine.startsWith('event:')) event = rawLine.slice(6).trim()
        if (rawLine.startsWith('data:')) dataLines.push(rawLine.slice(5).trim())
      }
      if (!dataLines.length) return
      let data
      try {
        data = JSON.parse(dataLines.join('\n'))
      } catch {
        return
      }
      if (event === 'error') {
        const error = new Error(data.message || '问答生成失败')
        error.code = data.code
        error.traceId = data.traceId || ''
        throw error
      }
      if (typeof onEvent === 'function') onEvent(event, data)
    }

    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        dispatch(buffer.slice(0, boundary))
        buffer = buffer.slice(boundary + 2)
        boundary = buffer.indexOf('\n\n')
      }
    }
    buffer += decoder.decode()
    if (buffer.trim()) dispatch(buffer.trim())
  },
}
