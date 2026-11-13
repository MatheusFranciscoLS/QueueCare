# Arquitetura

Monólito modular Spring Boot + aplicação React, falando por API REST, com PostgreSQL.

```
React (frontend)  --HTTP/JSON-->  Spring Boot (backend)  --JPA-->  PostgreSQL
                                   |- web (controllers, DTOs)
                                   |- service (regras de negócio)
                                   |- repository (Spring Data)
                                   |- security (Spring Security, JWT ou sessão: decidir em ADR)
```

Decisões pendentes (cada uma vira ADR antes da implementação):
- ADR 0002: controle de concorrência e sobreposição de horários.
- ADR 0003: autenticação (proposta: JWT stateless com access token curto).
- Tratamento de fuso e horário de verão.

Fora de escopo no MVP: microsserviços, mensageria, multiunidade, pagamentos, notificações.
(Este documento cresce junto com o projeto.)
