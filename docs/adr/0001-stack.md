# ADR 0001: stack do QueueCare

- **Status:** Accepted
- **Data:** 2026-11-13

## Contexto
Projeto Full Stack de portfólio, desenvolvido por uma pessoa em ritmo de 4 a 6 horas por semana. Objetivo: fortalecer Java/Spring de negócio, nuvem/DevOps e documentação em inglês, sem repetir os projetos anteriores (React + Node, e Java + RabbitMQ na plataforma IoT).

## Alternativas consideradas
- Node/Express no back-end (o autor já domina): descartado, não fecha a lacuna de Java/Spring.
- Angular no front-end: descartado por ora; React já é conhecido e Angular fica de reserva se as vagas exigirem.
- Microsserviços e RabbitMQ: descartados; mensageria já foi demonstrada em outros projetos e o foco aqui é correção transacional.

## Decisão
React + TypeScript; Java 21 + Spring Boot, Spring Security, Spring Data JPA; PostgreSQL + Flyway; JUnit, MockMvc, Testcontainers e Playwright; Docker e GitHub Actions; fase 2 AWS + Terraform. Monólito modular em monorepo.

## Consequências
Uma única base de código fácil de rodar e revisar. Custo: aprender Spring Security e Testcontainers. Cloud só depois do sistema funcionando local.
