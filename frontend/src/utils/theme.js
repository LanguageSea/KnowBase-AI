export function getTheme() {
  try {
    return localStorage.getItem('kb_theme') || 'light'
  } catch (e) {
    return 'light'
  }
}

export function applyTheme(theme) {
  const value = theme === 'dark' ? 'dark' : 'light'
  document.documentElement.dataset.theme = value
  try {
    localStorage.setItem('kb_theme', value)
  } catch (e) {
    /* ignore */
  }
  return value
}

export function initTheme() {
  return applyTheme(getTheme())
}
