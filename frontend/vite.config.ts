import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Em desenvolvimento, chamadas para /api vão para a API Spring Boot local.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  preview: {
    // A pré-visualização do build (medição de peso, ADR 0018) também fala com a API local.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    // Só os testes de comportamento (jsdom); a verificação em navegador real fica em e2e/ e roda com o Playwright (ADR 0018).
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
