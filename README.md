# QueueCare

Plataforma de agendamento e fila de atendimento para pequenos prestadores de serviço (oficina de assistência técnica, clínica pequena, barbearia, consultoria).

> Em construção. Projeto Full Stack de portfólio: React + TypeScript, Java 21 + Spring Boot, PostgreSQL. Roteiro em [`docs/roadmap.md`](docs/roadmap.md).

## O que o sistema faz (MVP)
- Login e perfis: prestador, atendente e cliente.
- Cadastro de serviços e horários de atendimento.
- Página pública de agendamento, agendar e cancelar.
- Painel diário, check-in e fila simples, histórico.

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

# Frontend: lint e build
cd frontend
npm ci
npm run lint
npm run build
```

Banco local (PostgreSQL), na raiz do repositório:
```bash
cp .env.example .env   # troque as senhas
docker compose --env-file .env -f infra/docker-compose.yml up -d
```
A API lê `DB_URL`, `DB_USER`, `DB_PASSWORD` e **`JWT_SECRET`** (obrigatório, Base64 de pelo menos 32 bytes; gere com `openssl rand -base64 32`) do ambiente (veja `.env.example`). O esquema do banco só muda por migrations Flyway em `backend/src/main/resources/db/migration/`.

Estado atual: regras R1, R2 e R5, banco com a trava contra reserva dupla provada, serviço de agendamento e autenticação por JWT, todos testados. Ainda não há API de agendar/cancelar nem telas. Veja `docs/estudo/visao-geral.md`.

## Autoria
Projeto de portfólio de Matheus Francisco, desenvolvido sob a direção do dono do produto e **com assistência substancial de IA**: Claude implementa as partes técnicas (incluindo regras de negócio e testes) e o ChatGPT faz a revisão independente de cada mudança; o Matheus define o produto, aprova o rumo e recebe resumos de cada entrega. Não é uma demonstração de implementação individual. O processo está em [`CLAUDE.md`](CLAUDE.md) e o material de estudo em [`docs/estudo/`](docs/estudo/).

## Licença
[MIT](LICENSE).
