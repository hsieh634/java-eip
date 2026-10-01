import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 開發環境專用：瀏覽器只連 localhost:5173，/api 由 Vite 轉給後端 8080。
// 對瀏覽器而言 API 與頁面「同源」，所以 Cookie 照常運作，也不需要在後端設定 CORS。
// 正式環境沒有這個代理：前端建置後與後端同源提供（T06 處理）。
//
// E2E_API_TARGET／E2E_VITE_PORT 只給「隔離的 E2E 堆疊」使用（另一個後端＋測試資料庫＋另一個埠），
// 不影響平常的開發流程（未設定時就是 8080／5173）。
const apiTarget = process.env.E2E_API_TARGET ?? 'http://localhost:8080'
const port = Number(process.env.E2E_VITE_PORT ?? 5173)

export default defineConfig({
  plugins: [react()],
  server: {
    port,
    strictPort: true,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: false,
      },
    },
  },
})
