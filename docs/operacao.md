# Operação: deploy, rollback, monitoramento e incidentes

Roteiro curto para quem opera o QueueCare. Complementa `docs/publicacao.md` (como publicar do zero) e `docs/backup.md` (backup e restauração). Os limites dos planos gratuitos mudam: **confirme na página oficial de cada serviço** antes de depender de um número.

## 1. As peças e onde olhar

| Peça | Onde roda | O que olhar |
|---|---|---|
| Site (React) | Vercel | *Deployments*: cada merge em `main` publica sozinho |
| API (Spring Boot) | Render (plano gratuito) | *Logs* e *Events*; `/actuator/health/liveness` e `/readiness` |
| Banco (PostgreSQL 16) | Neon (plano gratuito) | Painel do Neon: estado, consumo e restauração |
| Código e CI | GitHub | *Actions*: build, testes, imagem, segredos, ensaio de backup |

Dois fatos que explicam quase todo "está lento" no plano gratuito:
- **A API dorme** depois de um tempo sem uso e leva cerca de um minuto para acordar. O site avisa "servidor acordando".
- **O banco dorme** (escala a zero) e a primeira consulta depois disso é mais lenta.

## 2. Saber se está de pé

- **`ops/smoke.sh <api> [site]`**: confere viva (liveness), fala com o banco (readiness), devolve `X-Request-Id`, responde 401 e não 500 numa rota protegida e o site responde. Sai com erro e diz o que falhou. Rode depois de cada deploy.
- **`/actuator/health/liveness`**: só diz que o processo está vivo (não consulta o banco, então o banco dormindo não faz o Render reiniciar a API em loop). **`/actuator/health/readiness`**: inclui o banco.
- **A API dorme e a primeira chamada leva até ~2 minutos** (medido: 115 s). O que a acorda é o **próprio site, ao abrir** ([ADR 0037](adr/0037-acordar-a-api-ao-abrir-o-site.md)): uma chamada de vivacidade sem dado nenhum, para a espera correr enquanto a pessoa lê a página.
- **Monitor agendado no GitHub** ([ADR 0032](adr/0032-api-acordada-e-alerta.md), `.github/workflows/monitor.yml`): **não é confiável para manter a API acordada**: o GitHub atrasa por horas ou pula execuções agendadas deste repositório (medido em 24/11/2026: nenhuma execução agendada em 15 h). Serve como verificação **manual**: *Actions → Monitor da API → Run workflow* roda a vivacidade e o `ops/smoke.sh` completo, e fica vermelho se algo falhar.
- **Pingador externo gratuito, restrito ao horário comercial** (opcional, só o dono pode criar a conta): no cron-job.org (ou similar), um job HTTP `GET https://<api>/actuator/health/liveness` **de 10 em 10 minutos, das 08h às 20h de Brasília, de segunda a sábado**. Nunca 24 h por dia: o Render gratuito dá 750 h por mês para a **conta inteira** (há outros projetos nela) e um serviço sempre acordado consome quase tudo. Não use `readiness`: mantém o Neon acordado e gasta a cota dele.

## 3. Achar a causa de um erro: o código da requisição

Toda resposta da API traz `X-Request-Id`, e **toda linha de log daquela chamada leva o mesmo código** (`rid=...`). Quando o app mostra "Algo deu errado no servidor… informe o código a1b2c3d4e5f60718":

1. Abra os *Logs* da API no Render e busque por `rid=a1b2c3d4e5f60718`.
2. Você vê todas as linhas daquela chamada, com a exceção e o rastro, sem adivinhar por horário.
3. Se o log já rolou, use o horário do erro como segunda pista. O Render gratuito guarda pouco histórico: não conte com logs antigos.

Um código vindo de fora só é aceito se tiver 8 a 64 caracteres entre letras, dígitos, `.`, `-` e `_`; qualquer outra coisa é descartada e substituída (ninguém injeta linhas falsas no log).

## 4. Publicar (deploy)

**Antes:** CI verde (inclui o ensaio de restauração); se há migração nova, ler como ela se comporta em dados que já existem; ter um backup recente (*Actions → Backup diário*, se estiver ligado).

**Publicar:** merge em `main`. **A Vercel publica o site e o Render publica a API sozinhos, o Render só depois que os checks do GitHub passam** (`autoDeployTrigger: checksPass` no `render.yaml`; no painel: *Settings → Build & Deploy → Auto-Deploy → After CI checks pass*). **Só uma mudança no backend (ou no `render.yaml`) publica a API**: o filtro de caminhos (`buildFilter` no `render.yaml`; no painel, *Settings → Build & Deploy → Build Filters → Included Paths*: `backend/**` e `render.yaml`) evita reiniciar a API de produção por um README, um doc ou uma mudança só do site, que a Vercel publica por conta própria. A API leva alguns minutos (primeira subida ~2 min no plano gratuito); as migrações do Flyway rodam na subida, pela URL direta do banco. Se o painel e o `render.yaml` divergirem, vale o painel: confira os dois quando mexer em um.

> **Por que isso importa:** em 23/11/2026 a API em produção estava parada no commit de 14/11 (banco na versão 6 das migrações) enquanto o site, que publica sozinho, já chamava rotas novas. Nada avisava. Por isso a API também publica sozinha. **Consequência: cada merge de backend roda migrações em produção, e o Flyway não tem "desfazer"**, então a regra de expandir e contrair abaixo é obrigação, não recomendação. Para uma migração arriscada (apaga ou reescreve dados), crie antes um branch do banco no Neon (*Branches → Create branch*: cópia instantânea e gratuita) e só então mescle. Depois de mesclar, confira com o `ops/smoke.sh` (o passo 3 exige o `X-Request-Id`, que só existe em versões recentes) e olhe os logs do Flyway na subida (`Current version of schema`).

**Depois:** `ops/smoke.sh <api> <site>` e entrar no app com uma conta de teste.

### Regra das migrações (a mais importante)
O Flyway só anda **para frente**: não existe "desfazer a migração". Por isso, mude o esquema em **duas etapas** que cada uma pode ser publicada e revertida sozinha:
1. **Expandir:** adicionar coluna/tabela nova, opcional, sem remover nada. O código antigo continua funcionando.
2. **Contrair:** só em um deploy **posterior**, depois de o código novo estar estável, remover o que ficou sem uso.
Nunca faça, no mesmo deploy, uma migração que quebra o código anterior: se for preciso voltar o código, ele não encontraria o que espera.

## 5. Voltar atrás (rollback)

| Problema | Como voltar |
|---|---|
| Site quebrado | Vercel → *Deployments* → o último que estava bom → **Promote to Production** (instantâneo, sem build) |
| API quebrada (código) | Render → *Events/Deploys* → o deploy anterior → **Rollback**. Só é seguro se a migração desse deploy foi do tipo "expandir" (seção 4) |
| Migração que estragou os dados | Não há desfazer: **restaurar o backup** (`docs/backup.md`, passo a passo). Depois, reaplicar as exclusões de conta pedidas desde o backup (`docs/privacidade-dados.md`) |
| Segredo vazou | Trocar `JWT_SECRET` (invalida todas as sessões) e a senha do banco; ver "Incidente" em `docs/privacidade-dados.md` |

## 6. Quando algo dá errado (checklist de 5 minutos)

1. **`ops/smoke.sh`** diz qual camada falhou (API, banco, site).
2. **Banco fora?** Painel do Neon: estado, cota de uso e limites do plano. Um banco que dormiu acorda sozinho; um que estourou a cota precisa de decisão.
3. **API fora?** Render: *Events* (deploy falhou? reiniciou?) e *Logs*. Procure pelo código da requisição do erro.
4. **Mudou algo agora há pouco?** O último deploy é o principal suspeito: faça o rollback da seção 5 **antes** de investigar a fundo.
5. **Dado perdido ou corrompido?** Pare de escrever, restaure o backup (`docs/backup.md`) e registre o que aconteceu.

## 7. Contingência: o sistema fora do ar com o estúdio aberto

O app guarda agenda e fila; se ele cair, o estúdio precisa continuar atendendo:
- Ter a **agenda do dia impressa ou em foto** ao abrir (a tela Agenda tem os horários e telefones; "Clientes" acha qualquer tutor atendido).
- Anotar chegadas e conclusões em papel e **lançar depois**: a tela permite chegada e conclusão no dia, e "Desfazer" corrige o que sair errado.
- Avisar os tutores pelo WhatsApp (o link da agenda abre a conversa com a mensagem pronta).

## 8. Segredos e configuração (onde ficam)

Nada disto está no repositório. Guarde cópia num gerenciador de senhas.

| Segredo | Onde fica | O que acontece se trocar |
|---|---|---|
| `JWT_SECRET` | Render (variável) | Todas as sessões caem: é o botão de emergência |
| `DB_URL`, `DB_DIRECT_URL`, `DB_USER`, `DB_PASSWORD` | Render | A API perde o banco até corrigir |
| `CORS_ALLOWED_ORIGINS` | Render | O site passa a ser recusado pela API. Desde 24/11/2026 aceita os DOIS endereços do site, separados por vírgula: `https://peloeponto.vercel.app` (o atual) e `https://queuecare-psi.vercel.app` (o antigo, que continua servindo o mesmo site); quando o antigo deixar de ser usado, tire-o da lista |
| `DEMO_ENABLED` | Render (variável, não é segredo) | `true` liga a demonstração pública (ADR 0041); `false` ou ausente a desliga: a rota some e as contas fictícias deixam de ser usadas. `DEMO_RESET_AFTER` (padrão `60m`) é o prazo da reposição dos dados |
| `BACKUP_DATABASE_URL`, `BACKUP_PASSPHRASE` | GitHub Actions (opcionais) | Sem a frase, os backups não abrem |
