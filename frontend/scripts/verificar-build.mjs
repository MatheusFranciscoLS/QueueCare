// Garante que o build de PRODUÇÃO não carrega a página de demonstração da fundação visual (ADR 0018, R2). (As fontes novas entraram na
// produção no R3, com a casca nova; só a demonstração continua proibida.) Procura, em TODOS os arquivos de dist/, o conteúdo e os nomes que só existem na demonstração.
// Uso: `npm run build && npm run check:build` (roda na CI depois do build).
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

const dist = resolve(import.meta.dirname, '..', 'dist')
const PROIBIDOS = ['/__ui', 'Fundação visual', 'DemoPage']

function arquivos(dir) {
  return readdirSync(dir).flatMap((nome) => {
    const caminho = join(dir, nome)
    return statSync(caminho).isDirectory() ? arquivos(caminho) : [caminho]
  })
}

const achados = []
for (const arquivo of arquivos(dist)) {
  const nome = arquivo.toLowerCase()
  const texto = /\.(js|css|html|map|json|txt)$/.test(nome) ? readFileSync(arquivo, 'utf8') : ''
  for (const termo of PROIBIDOS) {
    if (nome.includes(termo.toLowerCase()) || texto.includes(termo)) achados.push(`${arquivo.slice(dist.length + 1)}: "${termo}"`)
  }
}
if (achados.length > 0) {
  console.error('O build de produção contém o que só deveria existir em desenvolvimento:\n' + achados.join('\n'))
  process.exit(1)
}
console.log(`build de produção limpo: nenhum dos ${PROIBIDOS.length} marcadores da demonstração em ${arquivos(dist).length} arquivos`)
