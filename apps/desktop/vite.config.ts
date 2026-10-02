import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'
import { parseServerUrl } from './src/serverUrl'

// Port 1420 is Tauri's conventional dev port (matches src-tauri/tauri.conf.json).
export default defineConfig(({ mode }) => {
  // The server is built in (see src/serverUrl.ts). Fail here, like the native
  // app's #error, rather than ship a build with nothing to sign in to.
  parseServerUrl(loadEnv(mode, process.cwd(), 'VITE_').VITE_HALO_SERVER_URL)

  return {
    plugins: [react()],
    clearScreen: false,
    server: {
      port: 1420,
      strictPort: true,
      watch: {
        ignored: ['**/src-tauri/**'],
      },
    },
  }
})
