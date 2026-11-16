import { defineConfig } from '@playwright/test'

/**
 * Verificação em NAVEGADOR REAL (ADR 0018, seção 8): capturas, acessibilidade (axe), foco, rolagem horizontal, alvos de toque e peso.
 * Usa o Edge instalado (sem baixar navegador) e o ambiente local de desenvolvimento (API com PostgreSQL embutido e dados fictícios).
 * Não roda no CI do GitHub (o CI roda os testes de comportamento); é a verificação VISUAL de cada PR de tela.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: /.*\.spec\.ts/,
  outputDir: './e2e/saida/resultados',
  workers: 1, // a API é uma só e os relatórios são agregados em ordem
  timeout: 120_000,
  reporter: [['list']],
  use: {
    channel: 'msedge',
    baseURL: 'http://127.0.0.1:5173',
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    trace: 'off',
  },
  webServer: [
    { command: 'node e2e/dev-api.mjs', url: 'http://localhost:8080/actuator/health', reuseExistingServer: true, timeout: 240_000 },
    { command: 'npm run dev -- --host 127.0.0.1 --port 5173', url: 'http://127.0.0.1:5173', reuseExistingServer: true, timeout: 60_000 },
  ],
})
