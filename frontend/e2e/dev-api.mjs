// Sobe a API de desenvolvimento (PostgreSQL embutido + dados FICTÍCIOS de demonstração; ADR 0018, seção 9). Sem Docker.
// Uso: `npm run dev:api` (ou deixe o Playwright subi-la). Só desenvolvimento e verificação: nada disto vai para a produção.
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { delimiter, join, resolve } from 'node:path'

const backend = resolve(import.meta.dirname, '..', '..', 'backend')
const mvnw = join(backend, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')
const run = (args) => spawnSync(mvnw, args, { cwd: backend, stdio: 'inherit', shell: process.platform === 'win32' })

const cpFile = join(backend, 'target', 'dev-classpath.txt')
if (!existsSync(cpFile)) {
  // Uma vez: o classpath de teste (inclui o PostgreSQL embutido). Precisa de rede só nesta vez.
  const r = run(['-q', 'dependency:build-classpath', `-Dmdep.outputFile=${cpFile}`, '-Dmdep.includeScope=test'])
  if (r.status !== 0) process.exit(r.status ?? 1)
}
const compiled = run(['-q', 'test-compile'])
if (compiled.status !== 0) process.exit(compiled.status ?? 1)

const classpath = [join(backend, 'target', 'test-classes'), join(backend, 'target', 'classes'), readFileSync(cpFile, 'utf8').trim()].join(delimiter)
// O classpath vai por `java -cp` (e não pelo plugin do Spring Boot, que no Windows interpreta "\t" de "target\test-classes" como tabulação).
// O MESMO Java com que o Maven compilou: o mvnw usa o JAVA_HOME; chamar só "java" pegaria o do PATH, que pode ser mais antigo (UnsupportedClassVersionError, "class file version 65.0").
const javaHome = process.env.JAVA_HOME
const javaBin = javaHome && existsSync(join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')) ? join(javaHome, 'bin', 'java') : 'java'
const java = spawn(javaBin, ['-cp', classpath, 'com.queuecare.dev.LocalDevServer'], { cwd: backend, stdio: 'inherit' })
java.on('exit', (code) => process.exit(code ?? 0))
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => java.kill())
