import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      // 开发时前端跑在 5173、后端跑在 8080,直接跨域会被浏览器拦。
      // 用 Vite 的代理把 /api 转发到后端,前端代码里只写 /api/xxx 就行。
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 流式响应必须关掉代理缓冲,否则后端一个字一个字发出来,
        // 代理会先攒成一大块再给浏览器 —— 那就失去流式的意义了。
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, _req, res) => {
            const contentType = proxyRes.headers['content-type'] ?? ''
            if (contentType.includes('text/event-stream')) {
              res.setHeader('Cache-Control', 'no-cache, no-transform')
              res.setHeader('X-Accel-Buffering', 'no')
            }
          })
        },
      },
    },
  },
})
