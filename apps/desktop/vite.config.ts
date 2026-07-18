import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Port 1420 is Tauri's conventional dev port (matches src-tauri/tauri.conf.json).
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
  },
})
