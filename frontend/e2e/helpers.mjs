// E2E 腳本共用工具。前置條件與環境變數說明見 smoke.mjs 開頭。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const here = path.dirname(fileURLToPath(import.meta.url))

export const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
export const OUT = path.join(here, 'output') // 截圖輸出，已被 .gitignore 排除

export function readSeedPassword() {
  if (process.env.RENEWFLOW_DEV_SEED_PASSWORD) return process.env.RENEWFLOW_DEV_SEED_PASSWORD
  const envFile = path.resolve(here, '../../.env')
  if (fs.existsSync(envFile)) {
    const line = fs.readFileSync(envFile, 'utf8').split(/\r?\n/).find((l) => l.startsWith('RENEWFLOW_DEV_SEED_PASSWORD='))
    if (line) return line.slice(line.indexOf('=') + 1)
  }
  throw new Error('找不到 RENEWFLOW_DEV_SEED_PASSWORD（環境變數或根目錄 .env）')
}

function findBrowser() {
  const candidates = [
    process.env.BROWSER_PATH,
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  ]
  const found = candidates.find((p) => p && fs.existsSync(p))
  if (!found) throw new Error('找不到 Edge／Chrome，請設定 BROWSER_PATH')
  return found
}

export function launchBrowser() {
  fs.mkdirSync(OUT, { recursive: true })
  return puppeteer.launch({ executablePath: findBrowser(), headless: true })
}

/** 收集結果並在最後依失敗數決定結束碼。 */
export function createRunner() {
  const results = []
  return {
    check(name, ok, detail = '') {
      results.push({ name, ok })
      console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
    },
    finish() {
      const failed = results.filter((r) => !r.ok)
      console.log(`\n${results.length - failed.length}/${results.length} passed`)
      process.exit(failed.length ? 1 : 0)
    },
  }
}

/**
 * 在頁面上記錄未捕捉的例外與「未處理的 Promise 拒絕」。
 * 後者用 unhandledrejection 事件直接記錄，不依賴瀏覽器是否把它當成 pageerror。
 */
export async function trackPageErrors(page) {
  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e)))
  await page.evaluateOnNewDocument(() => {
    globalThis.__unhandledRejections = []
    globalThis.addEventListener('unhandledrejection', (event) => {
      globalThis.__unhandledRejections.push(String(event.reason))
    })
  })
  return {
    pageErrors,
    unhandledRejections: () => page.evaluate(() => globalThis.__unhandledRejections ?? []),
  }
}

export async function loginAs(page, username, password) {
  await page.goto(BASE, { waitUntil: 'networkidle0' })
  await page.waitForSelector('#username')
  await page.type('#username', username)
  await page.type('#password', password)
  await page.click('button[type=submit]')
  await page.waitForSelector('.topbar')
}
