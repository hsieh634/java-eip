// 隔離的 E2E 堆疊：寫入型的 E2E（打卡）不能碰使用者的開發帳號與出勤資料，也不能靠清空資料才能重跑。
// 做法：另外啟動「一組」專用服務——
//   後端：spring-boot:test-run，profile=e2e，埠 8081，連【測試資料庫】（不是開發庫）
//   前端：另一個 Vite，埠 5174，/api 代理到 8081
//   員工：每次執行由後端的 E2eSeedRunner 建立全新的專用帳號（帳號含執行識別碼，密碼是一次性隨機值）
// 因為每次都是新帳號，重複執行不需要刪除任何東西。結束時兩個行程都會關閉。
import { spawn, spawnSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { OUT } from './helpers.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendDir = path.resolve(here, '..')
const backendDir = path.resolve(here, '../../backend')

const API_PORT = Number(process.env.E2E_API_PORT ?? 8081)
const WEB_PORT = Number(process.env.E2E_VITE_PORT ?? 5174)

const isWindows = process.platform === 'win32'

function start(name, command, args, options) {
  fs.mkdirSync(OUT, { recursive: true })
  const log = fs.openSync(path.join(OUT, `${name}.log`), 'w')
  // Windows 上 .cmd 檔需要經由 cmd.exe 啟動
  const child = isWindows
    ? spawn('cmd.exe', ['/c', command, ...args], { ...options, stdio: ['ignore', log, log], windowsHide: true })
    : spawn(command, args, { ...options, stdio: ['ignore', log, log] })
  return child
}

function kill(child) {
  if (!child?.pid) return
  if (isWindows) {
    spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    child.kill('SIGTERM')
  }
}

async function waitFor(url, { accept, timeoutMs, label, child }) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`${label} 提前結束（exit ${child.exitCode}），請查看 e2e/output/ 內的日誌`)
    try {
      const response = await fetch(url)
      if (accept(response.status)) return
    } catch {
      // 尚未啟動
    }
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error(`${label} 在 ${timeoutMs / 1000} 秒內沒有就緒`)
}

async function assertPortFree(port) {
  try {
    await fetch(`http://localhost:${port}/`)
  } catch {
    return // 連不上＝沒有人在用
  }
  throw new Error(`埠 ${port} 已被占用；隔離堆疊不會使用既有的服務（避免碰到你的開發環境）。請關閉占用者後再試。`)
}

/** 啟動隔離堆疊。回傳 { baseUrl, users:{a,b,c}, stop }。 */
export async function startIsolatedStack() {
  await assertPortFree(API_PORT)
  await assertPortFree(WEB_PORT)

  const runId = Date.now().toString(36)
  const password = `E2e-${crypto.randomBytes(9).toString('hex')}` // 一次性，不寫入任何檔案、不輸出
  const env = {
    ...process.env,
    RENEWFLOW_E2E_RUN_ID: runId,
    RENEWFLOW_E2E_PASSWORD: password,
    E2E_API_PORT: String(API_PORT),
  }

  const backend = start('e2e-backend', isWindows ? String.raw`.\mvnw.cmd` : './mvnw',
    ['-B', '-q', 'spring-boot:test-run', '-Dspring-boot.run.profiles=e2e'], { cwd: backendDir, env })
  let web
  const stop = () => {
    kill(web)
    kill(backend)
  }
  try {
    await waitFor(`http://localhost:${API_PORT}/api/auth/me`, { accept: (s) => s === 401, timeoutMs: 180_000, label: '隔離後端', child: backend })
    web = start('e2e-web', isWindows ? 'npm.cmd' : 'npm', ['run', 'dev'],
      { cwd: frontendDir, env: { ...env, E2E_API_TARGET: `http://localhost:${API_PORT}`, E2E_VITE_PORT: String(WEB_PORT) } })
    await waitFor(`http://localhost:${WEB_PORT}/`, { accept: (s) => s === 200, timeoutMs: 60_000, label: '隔離前端', child: web })
  } catch (error) {
    stop()
    throw error
  }

  const user = (letter) => ({ username: `e2e_${letter}_${runId}`, password })
  return { baseUrl: `http://localhost:${WEB_PORT}`, users: { a: user('a'), b: user('b'), c: user('c') }, stop }
}
