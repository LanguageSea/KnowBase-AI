import { ElMessage } from 'element-plus'

/**
 * 统一错误提示：正文取后端 message，附带 traceId 便于排查。
 */
export function notifyError(err) {
  const message = (err && err.message) || '请求失败，请稍后重试'
  const traceId = err && err.traceId
  ElMessage({
    type: 'error',
    message: traceId ? `${message}（traceId: ${traceId}）` : message,
    showClose: true,
    duration: 6000,
    grouping: true,
  })
}

export function notifySuccess(message) {
  ElMessage({ type: 'success', message, duration: 2500 })
}

export function notifyWarning(message) {
  ElMessage({ type: 'warning', message, duration: 3000 })
}
