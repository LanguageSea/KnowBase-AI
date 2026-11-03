import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    base: env.VITE_SHOWCASE_BASE || '/',
    build: {
      target: 'es2022',
      sourcemap: false,
    },
  }
})
