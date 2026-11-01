import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物内嵌 emonlens-server（部署形态：单 jar 自带控制台）
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    port: 5175,
    proxy: {
      '/api': { target: 'http://localhost:8300', changeOrigin: true }
    }
  },
  build: {
    outDir: fileURLToPath(new URL('../emonlens-server/src/main/resources/static', import.meta.url)),
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // 公共依赖拆成独立 vendor chunk：发版只更新业务包，vendor 命中一年强缓存
        manualChunks: {
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          'naive-vendor': ['naive-ui'],
          'http-vendor': ['axios']
        }
      }
    }
  }
})
