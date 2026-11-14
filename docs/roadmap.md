# Roadmap: 7 semanas (4 a 6 h por semana)

A semana é referência de planejamento, não um limite: abra o PR assim que a funcionalidade estiver pronta.

## Fase 1: produto e Java/Spring (semanas 1 a 3)
- **M1 Fundação (S1):** regras, modelo relacional, repositório, Spring Boot, PostgreSQL + Flyway + Docker Compose, primeiras migrations, CI inicial, ADR 0002 (concorrência). *Pronto:* a aplicação sobe, conecta ao banco, Docker e CI funcionam.
- **M2 Autenticação (S2):** cadastro, login, perfis, autorização.
- **M3 Agendamento (S2 a S3):** disponibilidade, reservas, cancelamento e concorrência testados.
- **M4 Operação (S3):** painel diário, check-in, fila simples, histórico; front React e integração. *Pronto (14/11/2026):* agenda do dia, chegada, conclusão e fila (ADR 0011), telas do prestador, do atendente, do cliente e do ADMIN.

## Fase 2: nuvem e DevOps (semanas 4 e 5)
- **M5 Nuvem:** Docker do front e do back, segredos, Terraform básico, **alerta de custo antes de criar qualquer recurso AWS**, deploy por pipeline, smoke test, documentar como destruir tudo. Só começa com o sistema rodando localmente com Docker, PostgreSQL e CI.

## Fase 3: qualidade e apresentação internacional (semanas 6 e 7)
- **M6 E2E (S6):** Playwright (reservar, cancelar, check-in, permissões), OpenAPI, testes de conflito e erro.
- **M7 Entrega (S7):** README, arquitetura, API e mensagens de erro em inglês, vídeo curto, treino para explicar as 5 regras e os 6 testes.

## Cada marco só termina com
Implementação funcional · testes passando · revisão independente · documentação atualizada · resumo e perguntas de estudo em `docs/estudo/`.

## Fora do escopo
Pagamentos, WhatsApp/SMS, app mobile, múltiplas unidades, recorrência avançada, IA, integrações externas, RabbitMQ, fila em tempo real complexa.
