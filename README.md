# QueueCare

[![CI](https://github.com/MatheusFranciscoLS/QueueCare/actions/workflows/ci.yml/badge.svg)](https://github.com/MatheusFranciscoLS/QueueCare/actions/workflows/ci.yml)

Agendamento e fila de atendimento para **estúdios de banho e tosa** (marca "Pelo & Ponto"). Projeto Full Stack de portfólio: **Java 21 + Spring Boot**, **React + TypeScript**, **PostgreSQL**. English version: [README.en.md](README.en.md).

O sistema foi construído com a qualidade e as funções que um estúdio de verdade usaria no dia a dia (agenda, fila, ficha do pet, faltas, lista de espera, privacidade, backup), mas é um projeto de portfólio: não há estúdio real por trás dele.

## Veja funcionando

- **Site publicado:** [queuecare-psi.vercel.app](https://queuecare-psi.vercel.app). A API roda no plano gratuito do Render e dorme quando ninguém usa: a primeira abertura depois de um período parado pode levar até 2 minutos (a tela avisa, e o site já começa a acordá-la ao abrir). Ali você cria uma conta de tutor, cadastra um pet e agenda. O lado do estúdio (agenda, fila, clientes, resumo) roda no comando abaixo, com dados fictícios.
- **Tudo em um comando, sem Docker** (JDK 21 e Node 22 ou superior; a primeira vez baixa as dependências):

  ```bash
  cd frontend
  npm ci
  npm run demo
  ```

  Sobe a API com PostgreSQL embutido e dados fictícios e o site em `http://127.0.0.1:5173`, e imprime as contas (dono, atendente, tutora e administrador; a senha é a mesma para todas e aparece no terminal).

| Agenda do dia (estúdio) | Fila de atendimento (estúdio) |
|---|---|
| ![Agenda do dia](docs/img/agenda.jpg) | ![Fila de atendimento](docs/img/fila.jpg) |

| Resumo do estúdio | Agendar pelo celular (tutor) |
|---|---|
| ![Resumo do estúdio](docs/img/resumo.jpg) | ![Agendar pelo celular](docs/img/agendar-celular.jpg) |

## Decisões de engenharia que valem a conversa

| Desafio | Como foi resolvido |
|---|---|
| **Duas pessoas reservam o mesmo horário ao mesmo tempo** | A garantia está no **banco**: restrição de exclusão do PostgreSQL sobre intervalos (`tstzrange` + `btree_gist`), que também impede sobreposição entre serviços de durações diferentes. Testado com requisições simultâneas ([ADR 0002](docs/adr/0002-controle-de-concorrencia.md)). |
| **A resposta da reserva se perde e a pessoa tenta de novo** | Repetir o mesmo pedido devolve a reserva que já existe, em vez de um conflito com a própria reserva (relevante quando a API gratuita está acordando). Qualquer diferença continua sendo conflito ([ADR 0035](docs/adr/0035-reserva-idempotente.md)). |
| **Quem pode chamar o quê** | Autorização na API, não na interface. Uma **matriz perfil × rota gerada do contrato OpenAPI** roda no CI: rota nova sem regra declarada **quebra o build**. Recurso de outro usuário responde 404 igual ao inexistente. |
| **Horários, fusos e horário de verão** | Tudo em UTC, com o fuso IANA do estúdio; horário que não existe ou é ambíguo é recusado com mensagem clara. |
| **Privacidade (LGPD)** | O tutor baixa os próprios dados e exclui a conta (anonimização no lugar, sem quebrar o histórico); política versionada ([ADR 0027](docs/adr/0027-lgpd-minima.md)). |
| **Backup que se sabe restaurar** | Backup criptografado e **restauração ensaiada em cada PR** pela CI, que já pegou erros reais ([ADR 0028](docs/adr/0028-backup-e-restauracao.md)). |
| **Acessibilidade medida, não presumida** | axe-core, foco visível, alvos de toque de 44 px e ausência de rolagem horizontal, em claro e escuro, em cada tela ([ADR 0018](docs/adr/0018-redesign.md)). |
| **Operar e diagnosticar** | Código de requisição em cada resposta e em cada linha de log, **uma linha de acesso por chamada com rota, status e duração e sem dado pessoal**, roteiro de deploy e rollback, e deploy automático depois da CI ([ADR 0029](docs/adr/0029-identificador-de-requisicao-e-operacao.md), [ADR 0036](docs/adr/0036-log-de-acesso.md), [operação](docs/operacao.md)). |

Cada decisão tem um [ADR](docs/adr/) com o que foi **descartado** e os **riscos conhecidos**, inclusive os de rodar a custo zero ([ADR 0013](docs/adr/0013-hospedagem-custo-zero.md)).

## O que o sistema faz

Para o **tutor**: cadastra os pets (porte, pelo, peso, comportamento, saúde, vacinas), agenda escolhendo serviço, pet, dia e horário (duração e valor seguem o porte e o pelo), vê até quando cancela de graça, acompanha o atendimento e entra na lista de espera quando o dia está cheio. Para o **estúdio**: agenda do dia e da semana, fila com os estados do atendimento, ficha do pet com aviso de comportamento, registro de faltas, cadastro e reserva pelo balcão, folgas e feriados, resumo e clientes. Para o **administrador**: contas. O detalhe está em [`docs/funcionalidades.md`](docs/funcionalidades.md).

## Stack

- **Backend:** Java 21, Spring Boot 4, Spring Security (JWT), JPA, Flyway, PostgreSQL 16. Contrato [OpenAPI](docs/openapi.json) gerado do código e conferido por teste.
- **Frontend:** React 19, TypeScript, Vite; testes com Vitest e E2E com Playwright.
- **Entrega:** GitHub Actions (testes, imagem Docker, restauração de backup, lint, varredura de segredos), Vercel, Render e Neon, todos no plano gratuito.

## Estrutura
```
backend/   API Java (Spring Boot)
frontend/  Aplicação React + TypeScript
infra/     Docker Compose
ops/       scripts de operação (backup, restauração, verificação pós-deploy)
docs/      regras, modelo de dados, arquitetura, ADRs, operação e roadmap
```

## Como rodar
Pré-requisitos: JDK 21, Node 22 ou superior e, para um banco de verdade, Docker (ou PostgreSQL 16). O Maven vem junto, pelo Maven Wrapper (`mvnw`). A forma mais rápida é o `npm run demo` acima; abaixo, os testes e o modo com banco.

```bash
# Backend: build e testes (usam PostgreSQL embutido, não precisa de banco instalado)
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
A API lê `DB_URL`, `DB_USER`, `DB_PASSWORD` e **`JWT_SECRET`** (obrigatório, Base64 de pelo menos 32 bytes; gere com `openssl rand -base64 32`) do ambiente (veja `.env.example`). O esquema do banco só muda por migrations do Flyway.

Primeiro administrador (uma vez só, por comando explícito, sem senha em arquivo): veja [`docs/adr/0005-provisionamento-de-contas.md`](docs/adr/0005-provisionamento-de-contas.md). Publicação gratuita (sem cartão): [`docs/publicacao.md`](docs/publicacao.md).

## Documentação
[Funcionalidades](docs/funcionalidades.md) · [Regras de negócio](docs/regras.md) · [Modelo de dados](docs/modelo-dados.md) · [Arquitetura](docs/arquitetura.md) · [ADRs](docs/adr/) · [Operação](docs/operacao.md) · [Backup](docs/backup.md) · [Privacidade](docs/privacidade-dados.md) · [Roadmap](docs/roadmap.md)

## Autoria
Projeto de portfólio de Matheus Francisco, desenvolvido sob a direção do dono do produto e **com assistência substancial de IA**: Claude implementa as partes técnicas (incluindo regras de negócio e testes) e o ChatGPT faz a revisão independente de cada mudança; o Matheus define o produto, aprova o rumo e recebe resumos de cada entrega. Não é uma demonstração de implementação individual. O processo está em [`CLAUDE.md`](CLAUDE.md) e o material de estudo em [`docs/estudo/`](docs/estudo/).

## Licença
[MIT](LICENSE).
