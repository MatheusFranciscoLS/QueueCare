import { defineConfig } from '@playwright/test'

/**
 * Verificação em NAVEGADOR REAL (ADR 0018, seção 8): capturas, acessibilidade (axe), foco, rolagem horizontal, alvos de toque e peso.
 * Usa o Edge instalado (sem baixar navegador) e o ambiente local de desenvolvimento (API com PostgreSQL embutido e dados fictícios).
 * No computador de quem desenvolve usa o Edge instalado. No CI (variável CI do GitHub Actions) usa o Chromium baixado pelo próprio Playwright, roda só os fluxos de
 * COMPORTAMENTO (script `e2e:ci`, ADR 0042) e guarda o rastro de quem falhar; as capturas e as verificações visuais continuam sendo de cada PR de tela, na máquina local.
 */
const ci = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  testMatch: /.*\.spec\.ts/,
  outputDir: './e2e/saida/resultados',
  workers: 1, // a API é uma só e os relatórios são agregados em ordem
  timeout: 120_000,
  forbidOnly: ci, // um test.only esquecido não pode fazer o CI passar rodando só um teste
  retries: ci ? 1 : 0, // uma segunda chance só no CI: o rastro mostra se foi falha passageira (e o relatório do Playwright a marca como "flaky")
  reporter: ci ? [['list'], ['html', { open: 'never', outputFolder: 'e2e/saida/relatorio' }]] : [['list']],
  use: {
    ...(ci ? {} : { channel: 'msedge' }),
    baseURL: 'http://127.0.0.1:5173',
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    trace: ci ? 'retain-on-failure' : 'off',
  },
  webServer: [
    { command: 'node e2e/dev-api.mjs', url: 'http://localhost:8080/actuator/health', reuseExistingServer: true, timeout: 240_000 },
    { command: 'npm run dev -- --host 127.0.0.1 --port 5173', url: 'http://127.0.0.1:5173', reuseExistingServer: true, timeout: 60_000 },
  ],
})
