import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const baseUrl = process.env.SHOWCASE_URL || 'http://127.0.0.1:5173'
const executablePath =
  process.env.CHROME_PATH ||
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const artifactsDir = resolve('artifacts')

await mkdir(artifactsDir, { recursive: true })

const browser = await chromium.launch({
  executablePath,
  headless: true,
})

const errors = []

function watchPage(page, label) {
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(`${label}: ${message.text()}`)
  })
  page.on('pageerror', (error) => errors.push(`${label}: ${error.message}`))
}

async function assertPageHealthy(page, label) {
  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(500)

  const title = await page.title()
  if (!title.includes('知库 AI')) {
    throw new Error(`${label}: unexpected document title "${title}"`)
  }

  const sectionCount = await page.locator('main section').count()
  if (sectionCount < 7) {
    throw new Error(`${label}: only ${sectionCount} sections rendered`)
  }

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  if (overflow > 1) {
    throw new Error(`${label}: horizontal overflow is ${overflow}px`)
  }
}

async function revealAllSections(page) {
  await page.evaluate(async () => {
    const previousScrollBehavior = document.documentElement.style.scrollBehavior
    document.documentElement.style.scrollBehavior = 'auto'
    const step = Math.max(420, Math.round(window.innerHeight * 0.72))
    const max = document.documentElement.scrollHeight
    for (let y = 0; y < max; y += step) {
      window.scrollTo(0, y)
      await new Promise((resolve) => window.setTimeout(resolve, 90))
    }
    window.scrollTo(0, 0)
    document.documentElement.style.scrollBehavior = previousScrollBehavior
  })
  await page.waitForTimeout(350)
}

const workspaceUser = {
  id: 2,
  username: 'kbadmin',
  nickname: '知识库管理员',
  roles: ['USER', 'KB_ADMIN'],
}

function apiEnvelope(data) {
  return {
    code: 0,
    message: 'OK',
    data,
    traceId: 'showcase-e2e',
    timestamp: new Date().toISOString(),
  }
}

async function installWorkspaceMocks(page) {
  const generalAnswer =
    '知识库中未找到相关依据。\n\n这是基于通用知识的分析，不代表企业内部制度。'
  const sseBody = [
    'event: meta',
    'data: {"conversationId":101,"userMessageId":201,"messageId":202,"title":"Java GC 如何工作？"}',
    '',
    'event: retrieval',
    'data: {"count":0,"mode":"general_fallback","chatProvider":"fake-general","embeddingProvider":"local-hash-v1"}',
    '',
    'event: delta',
    'data: {"text":"知识库中未找到相关依据。\\n\\n"}',
    '',
    'event: delta',
    'data: {"text":"这是基于通用知识的分析，不代表企业内部制度。"}',
    '',
    'event: done',
    `data: ${JSON.stringify({
      conversationId: 101,
      messageId: 202,
      answer: generalAnswer,
      noAnswer: false,
      citations: [],
      chatProvider: 'fake-general',
      embeddingProvider: 'local-hash-v1',
      latencyMs: 320,
      status: 'OK',
    })}`,
    '',
    '',
  ].join('\n')

  await page.route('**/auth/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(apiEnvelope(workspaceUser)),
    }),
  )
  await page.route('**/knowledge-bases', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        apiEnvelope({
          items: [
            {
              id: 1,
              name: '产品文档',
              description: '面试展示知识库',
              docCount: 3,
              canWrite: true,
              canManage: true,
            },
          ],
          total: 1,
        }),
      ),
    }),
  )
  await page.route('**/qa/conversations**', (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() === 'GET' && url.pathname.endsWith('/qa/conversations')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(apiEnvelope({ items: [], total: 0 })),
      })
    }
    return route.continue()
  })
  await page.route('**/qa/stream', (route) =>
    route.fulfill({
      status: 200,
      headers: {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      body: sseBody,
    }),
  )
  return generalAnswer
}

async function setWorkspaceSession(page) {
  await page.evaluate((user) => {
    localStorage.setItem('kb_token', 'showcase-e2e-token')
    localStorage.setItem('kb_user', JSON.stringify(user))
  }, workspaceUser)
}

async function assertGlobalWorkspace(page, generalAnswer) {
  await page.getByRole('link', { name: '智能问答' }).click()
  await page.waitForURL('**/workspace')
  await page.getByRole('heading', { name: '全局知识问答' }).waitFor()
  const composer = page.getByPlaceholder(/输入问题，AI 会检索/)
  if (!(await composer.isEnabled())) {
    throw new Error('workspace: global composer should be enabled without selecting a KB')
  }
  const streamProbe = await page.evaluate(async () => {
    const response = await fetch('/qa/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ question: 'stream probe' }),
    })
    return { status: response.status, body: await response.text() }
  })
  if (streamProbe.status !== 200 || !streamProbe.body.includes('event: done')) {
    throw new Error(`workspace: mocked SSE is incomplete: ${JSON.stringify(streamProbe)}`)
  }
  const parsedProbe = await page.evaluate(async () => {
    const { qaApi } = await import('/src/api/index.js')
    const events = []
    await qaApi.stream(
      { question: 'parser probe' },
      { onEvent: (event, data) => events.push({ event, data }) },
    )
    return events
  })
  if (!parsedProbe.some((item) => item.event === 'done')) {
    throw new Error(`workspace: SSE parser missed done: ${JSON.stringify(parsedProbe)}`)
  }
  await composer.fill('Java GC 如何工作？')
  await page.evaluate(() => {
    window.__answerLengths = []
    window.__answerObserver = new MutationObserver(() => {
      const node = [...document.querySelectorAll('.answer-text')].at(-1)
      if (node) window.__answerLengths.push(node.textContent.trim().length)
    })
    window.__answerObserver.observe(document.body, {
      childList: true,
      characterData: true,
      subtree: true,
    })
  })
  await page.getByRole('button', { name: '发送', exact: true }).click()
  try {
    await page.waitForFunction((expected) => {
      const nodes = [...document.querySelectorAll('.answer-text')]
      const text = nodes.at(-1)?.textContent?.trim() || ''
      return text.length === expected.length
    }, generalAnswer)
  } catch (error) {
    const actual = await page.locator('.answer-text').last().textContent().catch(() => '')
    const cards = await page.locator('.answer-card').allTextContents().catch(() => [])
    const chatText = await page.locator('.chat-body').textContent().catch(() => '')
    throw new Error(
      `workspace: answer did not finish; expected=${JSON.stringify(generalAnswer)} actual=${JSON.stringify(actual)} cards=${JSON.stringify(cards)} chat=${JSON.stringify(chatText)}`,
      { cause: error },
    )
  }
  const typedProgressively = await page.evaluate((expectedLength) => {
    window.__answerObserver?.disconnect()
    return window.__answerLengths.some(
      (length) => length > 0 && length < expectedLength,
    )
  }, generalAnswer.length)
  if (!typedProgressively) {
    throw new Error('workspace: answer did not render progressively')
  }
  await page.getByText('通用知识', { exact: true }).waitFor()
  await page.screenshot({
    path: resolve(artifactsDir, 'integrated-workspace.png'),
    fullPage: false,
  })
}

try {
  const desktop = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  watchPage(desktop, 'desktop')
  await assertPageHealthy(desktop, 'desktop')

  await desktop.screenshot({
    path: resolve(artifactsDir, 'showcase-desktop-fold.png'),
    fullPage: false,
  })
  await revealAllSections(desktop)
  await desktop.screenshot({
    path: resolve(artifactsDir, 'showcase-desktop-full.png'),
    fullPage: true,
  })

  await desktop.getByRole('button', { name: /FastAPI/ }).click()
  await desktop.getByText('不解析 JWT、不查用户表、不做任何资源权限判断。').waitFor()

  await desktop.getByRole('tab', { name: /权限过滤/ }).click()
  await desktop
    .getByText('Java 统一处理身份与资源权限，Python 只做计算。撤权后下一个请求立即生效，不依赖缓存中的 JWT 权限。')
    .waitFor()

  await desktop.getByRole('button', { name: '复制代码' }).click()
  await desktop.getByRole('button', { name: '已复制' }).waitFor()

  const tablet = await browser.newPage({ viewport: { width: 1024, height: 768 } })
  watchPage(tablet, 'tablet')
  await assertPageHealthy(tablet, 'tablet')
  await tablet.screenshot({
    path: resolve(artifactsDir, 'showcase-tablet.png'),
    fullPage: false,
  })

  const mobile = await browser.newPage({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  })
  watchPage(mobile, 'mobile')
  await assertPageHealthy(mobile, 'mobile')
  await mobile.screenshot({
    path: resolve(artifactsDir, 'showcase-mobile-fold.png'),
    fullPage: false,
  })
  await revealAllSections(mobile)
  await mobile.screenshot({
    path: resolve(artifactsDir, 'showcase-mobile-full.png'),
    fullPage: true,
  })

  await mobile.getByRole('button', { name: '打开菜单' }).click()
  await mobile.locator('.mobile-panel').waitFor()
  await mobile.locator('.mobile-panel').getByRole('link', { name: /系统架构/ }).click()
  await mobile.waitForTimeout(500)

  const mobileMenuStillOpen = await mobile.locator('.mobile-panel').isVisible()
  if (mobileMenuStillOpen) {
    throw new Error('mobile: navigation menu did not close after selecting a section')
  }

  const entryPage = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  watchPage(entryPage, 'entry')
  await assertPageHealthy(entryPage, 'entry')
  await entryPage.getByRole('link', { name: '进入系统' }).first().click()
  await entryPage.waitForURL('**/login')
  await entryPage.getByLabel('用户名').waitFor()
  await entryPage.screenshot({
    path: resolve(artifactsDir, 'integrated-login.png'),
    fullPage: false,
  })
  await entryPage.getByRole('link', { name: '返回项目介绍' }).click()
  await entryPage.waitForURL(baseUrl)
  await entryPage.getByRole('heading', { name: /知库 AI/ }).waitFor()

  await entryPage.goto(`${baseUrl}/unknown-route`, { waitUntil: 'networkidle' })
  await entryPage.waitForURL(baseUrl)
  await entryPage.getByRole('heading', { name: /知库 AI/ }).waitFor()

  const workspacePage = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  watchPage(workspacePage, 'workspace')
  const mockGeneralAnswer = await installWorkspaceMocks(workspacePage)
  await workspacePage.goto(baseUrl, { waitUntil: 'networkidle' })
  await setWorkspaceSession(workspacePage)
  await workspacePage.goto(`${baseUrl}/workspace`, { waitUntil: 'networkidle' })
  await assertGlobalWorkspace(workspacePage, mockGeneralAnswer)
  await workspacePage.setViewportSize({ width: 390, height: 844 })
  const workspaceOverflow = await workspacePage.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  if (workspaceOverflow > 1) {
    throw new Error(`workspace: mobile horizontal overflow is ${workspaceOverflow}px`)
  }
  await workspacePage.screenshot({
    path: resolve(artifactsDir, 'integrated-workspace-mobile.png'),
    fullPage: false,
  })
  await workspacePage.getByRole('button', { name: '打开菜单' }).click()
  await workspacePage.locator('.mobile-panel').waitFor()
  await workspacePage.screenshot({
    path: resolve(artifactsDir, 'integrated-workspace-mobile-menu.png'),
    fullPage: false,
  })
  await workspacePage
    .locator('.mobile-panel')
    .getByRole('link', { name: '知识库', exact: true })
    .click()
  await workspacePage.waitForURL('**/knowledge-bases')
  if (await workspacePage.locator('.mobile-panel').isVisible()) {
    throw new Error('workspace: mobile navigation did not close after routing')
  }

  if (process.env.E2E_LIVE_LOGIN === '1') {
    const username = process.env.E2E_USERNAME || 'admin'
    const password = process.env.E2E_PASSWORD || 'Admin@123456'

    await entryPage.getByRole('link', { name: '进入系统' }).first().click()
    await entryPage.waitForURL('**/login')
    await entryPage.getByLabel('用户名').fill(username)
    await entryPage.getByLabel('密码').fill(password)
    await entryPage.getByRole('button', { name: '登录', exact: true }).click()
    await entryPage.waitForURL('**/knowledge-bases')
    await entryPage.getByRole('link', { name: '项目展示' }).waitFor()
    await entryPage.screenshot({
      path: resolve(artifactsDir, 'integrated-console.png'),
      fullPage: false,
    })
    const liveGeneralAnswer = await installWorkspaceMocks(entryPage)
    await assertGlobalWorkspace(entryPage, liveGeneralAnswer)

    await entryPage.getByRole('link', { name: '项目展示' }).click()
    await entryPage.waitForURL(baseUrl)
    await entryPage.getByRole('heading', { name: /知库 AI/ }).waitFor()
  }

  if (errors.length) {
    throw new Error(`browser console errors:\n${errors.join('\n')}`)
  }

  console.log('Showcase E2E passed')
  console.log(`Desktop screenshots: ${resolve(artifactsDir, 'showcase-desktop-full.png')}`)
  console.log(`Mobile screenshots: ${resolve(artifactsDir, 'showcase-mobile-full.png')}`)
} finally {
  await browser.close()
}
