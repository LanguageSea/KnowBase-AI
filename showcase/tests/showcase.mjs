import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const baseUrl = process.env.SHOWCASE_URL || 'http://127.0.0.1:4173'
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

async function assertPageHealthy(page, label) {
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(`${label}: ${message.text()}`)
  })
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

try {
  const desktop = await browser.newPage({ viewport: { width: 1440, height: 900 } })
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
  await mobile.getByRole('link', { name: /系统架构/ }).click()
  await mobile.waitForTimeout(500)

  const mobileMenuStillOpen = await mobile.locator('.mobile-panel').count()
  if (mobileMenuStillOpen) {
    throw new Error('mobile: navigation menu did not close after selecting a section')
  }

  if (errors.length) {
    throw new Error(`browser console errors:\n${errors.join('\n')}`)
  }

  if (process.env.EXPECT_PROJECT_ENTRY === '1') {
    const entryPage = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    await entryPage.goto(baseUrl, { waitUntil: 'networkidle' })
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
  }

  console.log('Showcase E2E passed')
  console.log(`Desktop screenshots: ${resolve(artifactsDir, 'showcase-desktop-full.png')}`)
  console.log(`Mobile screenshots: ${resolve(artifactsDir, 'showcase-mobile-full.png')}`)
} finally {
  await browser.close()
}
