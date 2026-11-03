function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function inline(text) {
  let out = escapeHtml(text)
  out = out.replace(/`([^`]+)`/g, '<code class="kb-md-code">$1</code>')
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  return out
}

export function renderMarkdown(text) {
  const raw = String(text ?? '')
  if (!raw.trim()) return ''
  const blocks = []
  const codePattern = /```(\w*)\n([\s\S]*?)(?:```|$)/g
  let last = 0
  let match = codePattern.exec(raw)
  while (match) {
    if (match.index > last) blocks.push({ type: 'text', value: raw.slice(last, match.index) })
    blocks.push({ type: 'code', lang: match[1] || 'text', value: match[2] })
    last = match.index + match[0].length
    match = codePattern.exec(raw)
  }
  if (last < raw.length) blocks.push({ type: 'text', value: raw.slice(last) })
  return blocks
    .map((block) => {
      if (block.type === 'code') {
        return `<pre class="kb-md-pre"><code>${escapeHtml(block.value.replace(/\n$/, ''))}</code></pre>`
      }
      return block.value
        .split(/\n{2,}/)
        .map((para) => `<p class="kb-md-p">${inline(para.trim()).replace(/\n/g, '<br />')}</p>`)
        .join('')
    })
    .join('')
}
