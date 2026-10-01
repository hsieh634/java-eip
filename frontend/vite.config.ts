import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 開發環境專用：瀏覽器只連 localhost:5173，/api 由 Vite 轉給後端 8080。
// 對瀏覽器而言 API 與頁面「同源」，所以 Cookie 照常運作，也不需要在後端設定 CORS。
// 正式環境沒有這個代理：前端建置後與後端同源提供（T06 處理）。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
})
