# QueueCare

Plataforma de agendamento e fila de atendimento para pequenos prestadores de serviço, hoje focada em **estúdios de banho e tosa** (marca "Pelo & Ponto"). English version: [README.en.md](README.en.md).

> Em construção. Projeto Full Stack de portfólio: React + TypeScript, Java 21 + Spring Boot, PostgreSQL. Roteiro em [`docs/roadmap.md`](docs/roadmap.md).

## O que o sistema faz
**Para o tutor (cliente):** cadastra os pets (porte, pelo, peso, comportamento, saúde, vacinas), agenda escolhendo serviço, pet, dia e horário (a duração e o valor seguem o porte e o pelo do pet; o app já mostra o primeiro dia com vaga), vê **até quando cancela ou remarca de graça** antes de confirmar e, passado o prazo, fala com o estúdio pelo WhatsApp; acompanha o atendimento (chegou, em atendimento, pronto para retirada), vê o histórico de cada pet e entra na **lista de espera** quando o dia está cheio.

**Para o estúdio (prestador e atendentes):** agenda do dia e da semana, fila com os estados do atendimento, ficha do pet na frente de quem atende (com aviso de comportamento), WhatsApp do tutor com mensagem pronta (pet pronto, **lembrete** do dia seguinte, chamada da lista de espera), registro de **faltas** com contador por tutor, serviços com preço e duração por porte e pelo, expediente, equipe, página pública e política de cancelamento configurável.

**Administração:** criação e manutenção de contas.

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

Estado atual (21/11/2026): produto de uso diário implementado em fatias com ADR cada: [0019](docs/adr/0019-uso-real-do-banho-e-tosa.md) (telefone, ficha do pet, preço, estados de atendimento, Início como painel, histórico do pet), [0020](docs/adr/0020-duracao-por-porte-e-pelo.md) (duração por porte e pelo), [0021](docs/adr/0021-retencao-de-clientes-e-faltas.md) (política visível, faltas e lembrete) e [0022](docs/adr/0022-lista-de-espera.md) (lista de espera). Redesign completo ([ADR 0018](docs/adr/0018-redesign.md)) com verificação em navegador real (acessibilidade, foco, rolagem e alvos de toque nas 84 combinações de tela, janela e tema). Próximo, proposto: [sinal por Pix manual (ADR 0023)](docs/adr/0023-sinal-por-pix-manual.md), a ligar só depois de dados reais de faltas. Rumo ao uso real: [folgas, feriados e férias (ADR 0024)](docs/adr/0024-folgas-feriados-e-ferias.md), [clientes do estúdio e senha temporária (ADR 0025)](docs/adr/0025-clientes-do-estudio-e-senha-temporaria.md) e [desfazer a última etapa do atendimento (ADR 0026)](docs/adr/0026-desfazer-etapa-do-atendimento.md).

## Autoria
Projeto de portfólio de Matheus Francisco, desenvolvido sob a direção do dono do produto e **com assistência substancial de IA**: Claude implementa as partes técnicas (incluindo regras de negócio e testes) e o ChatGPT faz a revisão independente de cada mudança; o Matheus define o produto, aprova o rumo e recebe resumos de cada entrega. Não é uma demonstração de implementação individual. O processo está em [`CLAUDE.md`](CLAUDE.md) e o material de estudo em [`docs/estudo/`](docs/estudo/).

## Licença
[MIT](LICENSE).
