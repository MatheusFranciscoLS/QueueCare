// Demonstração local em UM comando (`npm run demo`): sobe a API com PostgreSQL embutido e dados FICTÍCIOS (sem Docker) e o site, e diz onde abrir e com qual conta entrar.
// Só desenvolvimento e verificação (ADR 0018, seção 9): nada disto vai para a produção. Pré-requisitos: JDK 21 e Node 22 ou superior; a primeira vez baixa as dependências do Maven.
import { spawn } from 'node:child_process'
import { resolve } from 'node:path'

const frontend = resolve(import.meta.dirname, '..')
const SITE = 'http://127.0.0.1:5173'
const API_HEALTH = 'http://localhost:8080/actuator/health'
const PASSWORD = 'Senha-Demo-12345'
const ACCOUNTS = [
  ['dono do estúdio', 'helena@pelo.test'],
  ['atendente', 'bruno@pelo.test'],
  ['tutora (cliente)', 'marina@pelo.test'],
  ['administrador', 'admin@pelo.test'],
]

const children = []
const run = (command, args) => {
  const child = spawn(command, args, { cwd: frontend, stdio: 'inherit', shell: process.platform === 'win32' })
  children.push(child)
  child.on('exit', (code) => {
    if (code !== 0 && code !== null) {
      console.error(`\n"${command} ${args.join(' ')}" terminou com erro (${code}).`)
      stop(code)
    }
  })
  return child
}
function stop(code = 0) {
  for (const child of children) child.kill()
  process.exit(code)
}
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => stop(0))

console.log('Subindo a API de demonstração (a primeira vez demora: baixa as dependências e inicia o PostgreSQL embutido)...')
run('node', ['e2e/dev-api.mjs'])
run('npx', ['vite', '--host', '127.0.0.1', '--port', '5173'])

for (let i = 0; i < 240; i++) {
  try {
    const res = await fetch(API_HEALTH)
    if (res.ok) break
  } catch {
    // a API ainda está subindo
  }
  await new Promise((done) => setTimeout(done, 2000))
}

console.log(`
Pronto. Abra ${SITE}
Todas as contas usam a senha: ${PASSWORD}
${ACCOUNTS.map(([papel, email]) => `  ${papel.padEnd(18)} ${email}`).join('\n')}
(dados fictícios; Ctrl+C encerra tudo)
`)
