import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const target = 'http://127.0.0.1:8080'

function serveSpaForBrowserNavigation(req) {
  const acceptsHtml = String(req.headers.accept || '').includes('text/html')
  if (req.method === 'GET' && acceptsHtml) {
    return '/index.html'
  }
}

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/auth': { target, changeOrigin: true },
      '/admin': { target, changeOrigin: true, bypass: serveSpaForBrowserNavigation },
      '/knowledge-bases': { target, changeOrigin: true, bypass: serveSpaForBrowserNavigation },
      '/qa': { target, changeOrigin: true },
      '/health': { target, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router'],
          'element-plus': ['element-plus'],
        },
      },
    },
  },
})
