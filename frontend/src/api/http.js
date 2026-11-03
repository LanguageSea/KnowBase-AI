import axios from 'axios'
import { getToken, clearSession } from '../utils/auth'

const http = axios.create({
  baseURL: '',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

function makeApiError({ code = null, message = '请求失败', traceId = '', httpStatus = 0 }) {
  const err = new Error(message)
  err.name = 'ApiError'
  err.code = code
  err.traceId = traceId
  err.httpStatus = httpStatus
  return err
}

let handleUnauthorized = () => {
  clearSession()
  window.location.replace('/login')
}

/** 由 router 注入，避免 http <-> router 循环依赖 */
export function setUnauthorizedHandler(fn) {
  if (typeof fn === 'function') handleUnauthorized = fn
}

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 契约：统一响应体 { code, message, data, traceId, timestamp }
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) {
        return Promise.reject(
          makeApiError({
            code: body.code,
            message: body.message || '请求失败',
            traceId: body.traceId || response.headers['x-trace-id'] || '',
            httpStatus: response.status,
          }),
        )
      }
      return body.data
    }
    return body
  },
  (error) => {
    if (error.response) {
      const { status, data, headers, config } = error.response
      const traceId = (data && data.traceId) || headers['x-trace-id'] || ''
      if (status === 401) {
        clearSession()
        if (!(config && config.skipAuthRedirect)) {
          handleUnauthorized()
        }
      }
      return Promise.reject(
        makeApiError({
          code: data && data.code,
          message: (data && data.message) || `请求失败（HTTP ${status}）`,
          traceId,
          httpStatus: status,
        }),
      )
    }
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return Promise.reject(makeApiError({ message: '请求超时，请稍后重试' }))
    }
    return Promise.reject(makeApiError({ message: '网络异常，无法连接服务，请确认后端已启动' }))
  },
)

export default http
