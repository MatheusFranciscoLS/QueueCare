# QueueCare

Plataforma de agendamento e fila de atendimento para pequenos prestadores de serviço, hoje focada em **estúdios de banho e tosa** (marca "Pelo & Ponto"). English version: [README.en.md](README.en.md).

> Projeto Full Stack de portfólio, em evolução: React + TypeScript, Java 21 + Spring Boot, PostgreSQL. Funciona de ponta a ponta e é verificado a cada mudança (testes, acessibilidade, restauração de backup). Roteiro em [`docs/roadmap.md`](docs/roadmap.md).

## O que o sistema faz
**Para o tutor (cliente):** cadastra os pets (porte, pelo, peso, comportamento, saúde, vacinas), agenda escolhendo serviço, pet, dia e horário (a duração e o valor seguem o porte e o pelo do pet; o app já mostra o primeiro dia com vaga), vê **até quando cancela ou remarca de graça** antes de confirmar e, passado o prazo, fala com o estúdio pelo WhatsApp; acompanha o atendimento (chegou, em atendimento, pronto para retirada), vê o histórico de cada pet e entra na **lista de espera** quando o dia está cheio.

**Para o estúdio (prestador e atendentes):** agenda do dia e da semana, fila com os estados do atendimento, ficha do pet na frente de quem atende (com aviso de comportamento), WhatsApp do tutor com mensagem pronta (pet pronto, **lembrete** do dia seguinte, chamada da lista de espera), registro de **faltas** com contador por tutor, serviços com preço e duração por porte e pelo, expediente, equipe, página pública e política de cancelamento configurável.

**Administração:** criação e manutenção de contas.

### Pronto para uso real (ADRs 0024 a 0034)
O que separa uma demonstração de um sistema que um estúdio usa todo dia:

| Frente | O que existe |
|---|---|
| **Operação** | [Folgas, feriados e férias](docs/adr/0024-folgas-feriados-e-ferias.md) (o tutor não agenda em dia fechado); [Clientes](docs/adr/0025-clientes-do-estudio-e-senha-temporaria.md) (busca no balcão e senha temporária gerada pelo dono, sem e-mail); [cadastro e reserva pelo balcão](docs/adr/0033-cadastro-e-reserva-pelo-balcao.md) (a equipe agenda por quem liga ou chama no WhatsApp); [importação de clientes por planilha](docs/adr/0034-importacao-de-clientes.md) (CSV do Excel, lido no navegador); [desfazer a última etapa](docs/adr/0026-desfazer-etapa-do-atendimento.md) do atendimento; [Resumo do estúdio](docs/adr/0031-resumo-do-estudio.md) (atendimentos, valores com nome honesto, faltas e clientes que sumiram) |
| **Confiança e dados** | [Política de Privacidade, baixar os próprios dados e excluir a conta por anonimização](docs/adr/0027-lgpd-minima.md), com o [inventário de dados pessoais](docs/privacidade-dados.md) |
| **Recuperação** | [Backup criptografado e restauração ensaiada a **cada PR**](docs/adr/0028-backup-e-restauracao.md) (a CI restaura num segundo PostgreSQL e exige que seja idêntico), com o [passo a passo](docs/backup.md) |
| **Operação e suporte** | [Código de requisição em cada resposta e em cada linha de log](docs/adr/0029-identificador-de-requisicao-e-operacao.md), `ops/smoke.sh`, [API acordada no horário de atendimento e alerta de queda](docs/adr/0032-api-acordada-e-alerta.md) e o [roteiro de deploy, rollback e incidentes](docs/operacao.md) |
| **Uso no celular** | [App instalável que abre sem internet](docs/adr/0030-app-instalavel-e-offline.md) (o service worker nunca guarda dados da API) |

Cada decisão tem um ADR com o que foi **descartado** e os **riscos conhecidos**, inclusive os limites do custo zero.

Sem pagamento online e sem envio automático de mensagens (custo zero, [ADR 0013](docs/adr/0013-hospedagem-custo-zero.md)): o WhatsApp abre com a mensagem pronta e a equipe envia.

## Decisões técnicas centrais
- Sem reserva dupla, nem sob requisições simultâneas, e sem sobreposição entre serviços de durações diferentes (proteção no banco; ver [ADR 0002](docs/adr/0002-controle-de-concorrencia.md)).
- Horários em UTC com o fuso IANA do prestador.
- Autorização validada na API, não só na interface.

## Estrutura
```
backend/   API Java (Spring Boot)
frontend/  Aplicação React + TypeScript
infra/     Docker Compose; Terraform na fase 2
docs/      regras, modelo de dados, arquitetura, ADRs e roadmap
```

## Como rodar
Pré-requisitos: JDK 21, Node 22 ou superior e, para o banco, Docker (ou PostgreSQL 16 instalado). O Maven vem junto, pelo Maven Wrapper (`mvnw`).

```bash
# Backend: build e teste de arranque (não precisa de banco)
cd backend
./mvnw verify          # no Windows (PowerShell): .\mvnw.cmd verify

# Frontend: lint, testes e build
cd frontend
npm ci
npm run lint
npm test
npm run build
```

Banco local (PostgreSQL), na raiz do repositório:
```bash
cp .env.example .env   # troque as senhas
docker compose --env-file .env -f infra/docker-compose.yml up -d
```
A API lê `DB_URL`, `DB_USER`, `DB_PASSWORD` e **`JWT_SECRET`** (obrigatório, Base64 de pelo menos 32 bytes; gere com `openssl rand -base64 32`) do ambiente (veja `.env.example`). O esquema do banco só muda por migrations Flyway em `backend/src/main/resources/db/migration/`.

Publicação gratuita (sem cartão): roteiro em [`docs/publicacao.md`](docs/publicacao.md).

Contrato da API: [`docs/openapi.json`](docs/openapi.json) (OpenAPI, gerado do código e conferido por teste; ver [ADR 0012](docs/adr/0012-contrato-openapi.md)).

Primeiro administrador (uma vez só, por comando explícito, sem senha em arquivo): veja `docs/adr/0005-provisionamento-de-contas.md`.

Estado atual (21/11/2026): produto de uso diário implementado em fatias com ADR cada: [0019](docs/adr/0019-uso-real-do-banho-e-tosa.md) (telefone, ficha do pet, preço, estados de atendimento, Início como painel, histórico do pet), [0020](docs/adr/0020-duracao-por-porte-e-pelo.md) (duração por porte e pelo), [0021](docs/adr/0021-retencao-de-clientes-e-faltas.md) (política visível, faltas e lembrete) e [0022](docs/adr/0022-lista-de-espera.md) (lista de espera). Redesign completo ([ADR 0018](docs/adr/0018-redesign.md)) com verificação em navegador real (acessibilidade, foco, rolagem e alvos de toque nas 84 combinações de tela, janela e tema). Próximo, proposto: [sinal por Pix manual (ADR 0023)](docs/adr/0023-sinal-por-pix-manual.md), a ligar só depois de dados reais de faltas. Rumo ao uso real: [folgas, feriados e férias (ADR 0024)](docs/adr/0024-folgas-feriados-e-ferias.md), [clientes do estúdio e senha temporária (ADR 0025)](docs/adr/0025-clientes-do-estudio-e-senha-temporaria.md), [desfazer a última etapa do atendimento (ADR 0026)](docs/adr/0026-desfazer-etapa-do-atendimento.md) , [LGPD mínima (ADR 0027)](docs/adr/0027-lgpd-minima.md), com o [inventário de dados pessoais](docs/privacidade-dados.md), [backup criptografado com restauração ensaiada a cada PR (ADR 0028)](docs/adr/0028-backup-e-restauracao.md), com o [passo a passo](docs/backup.md), e [código de requisição nos logs e roteiro de operação (ADR 0029)](docs/adr/0029-identificador-de-requisicao-e-operacao.md), com o [roteiro de deploy, rollback e incidentes](docs/operacao.md), , [app instalável que abre sem internet (ADR 0030)](docs/adr/0030-app-instalavel-e-offline.md) e [resumo do estúdio, com clientes que sumiram (ADR 0031)](docs/adr/0031-resumo-do-estudio.md).

## Autoria
Projeto de portfólio de Matheus Francisco, desenvolvido sob a direção do dono do produto e **com assistência substancial de IA**: Claude implementa as partes técnicas (incluindo regras de negócio e testes) e o ChatGPT faz a revisão independente de cada mudança; o Matheus define o produto, aprova o rumo e recebe resumos de cada entrega. Não é uma demonstração de implementação individual. O processo está em [`CLAUDE.md`](CLAUDE.md) e o material de estudo em [`docs/estudo/`](docs/estudo/).

## Licença
[MIT](LICENSE).
