const TOKEN_KEY = 'kb_token'
const USER_KEY = 'kb_user'

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch (e) {
    return ''
  }
}

export function setToken(token) {
  try {
    localStorage.setItem(TOKEN_KEY, token)
  } catch (e) {
    /* localStorage 不可用时忽略，仅影响刷新后的会话恢复 */
  }
}

export function getCachedUser() {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

export function setCachedUser(user) {
  try {
    localStorage.setItem(USER_KEY, JSON.stringify(user || null))
  } catch (e) {
    /* ignore */
  }
}

export function clearSession() {
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  } catch (e) {
    /* ignore */
  }
}
