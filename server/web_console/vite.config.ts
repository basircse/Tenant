import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In development the API runs on :8980; set API_URL to use another one.
// The console lives under /console/ so the API can serve the production build (see deploy/README.md).
export default defineConfig({
  base: '/console/',
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: { '/api': process.env.API_URL ?? 'http://localhost:8980' },
  },
})
