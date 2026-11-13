import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Em desenvolvimento, chamadas para /api vão para a API Spring Boot local.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
