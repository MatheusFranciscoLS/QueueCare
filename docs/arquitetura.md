# Arquitetura

Monólito modular Spring Boot + aplicação React, falando por API REST (JSON), com PostgreSQL. Versão em inglês: [architecture.en.md](architecture.en.md).

```
Navegador (React + TypeScript, estático)
     |  HTTPS, JSON, token Bearer (JWT) só em memória
     v
API Spring Boot (Java 21)  --JPA/Flyway-->  PostgreSQL 16
  |- web        controllers, DTOs, tratamento de erros (problem+json com `reason` estável)
  |- application serviços com as regras de negócio e transações
  |- persistence entidades e repositórios (Spring Data)
  |- security   Spring Security (resource server JWT), limites de requisição, filtros
```

## Perfis e acesso
ADMIN (contas, sem acesso a agendas), PROVIDER (negócio, serviços, expediente, equipe, agenda), ATTENDANT (agenda, chegada, conclusão, fila) e CUSTOMER (catálogo, agendar, meus agendamentos). A autorização é validada **na API** (rota e `@PreAuthorize`), não só na interface; o prestador de quem opera vem sempre do banco, nunca da requisição (ADR 0004).

## Decisões centrais (ADRs em `docs/adr/`)
| ADR | Decisão |
|---|---|
| 0002 | Sem reserva dupla nem sobreposição, nem sob concorrência: restrição de exclusão no PostgreSQL (`btree_gist`) |
| 0003 | JWT HS256 curto, validado contra o banco a cada requisição; limites por minuto e bloqueio de login |
| 0004 | Vínculo atendente-prestador resolvido no servidor |
| 0005/0006/0007 | Provisionamento de contas, ciclo de credenciais, administração e auditoria |
| 0008/0009 | Front: token só em memória, padrões das telas (estado confirmado, erro na lista, foco) |
| 0010 | Catálogo de leitura para o cliente |
| 0011 | Chegada, conclusão e fila do dia (transições só para frente, evento único por tipo) |
| 0012 | Contrato OpenAPI gerado do código e conferido por teste |
| 0013 | Hospedagem de custo zero e sem cartão (Cloudflare Pages + Render Free + Neon Free) |

## Concorrência e integridade (resumo)
Duas camadas: validação na aplicação (transação) e garantia no banco (restrição de exclusão sobre `tstzrange`). Bloqueio otimista (`version`) nas transições do agendamento; bloqueios de linha e `lock_timeout` por transação nas operações administrativas (todos transacionais, compatíveis com pooler de conexões). Eventos do histórico com `UNIQUE (appointment_id, event_type)`.

## Fuso e horário
Instantes em UTC (`timestamptz`); cada prestador tem um fuso IANA. O cliente envia o horário LOCAL do negócio e a API resolve (horário inexistente ou ambíguo de verão é recusado, não adivinhado). A tela mostra datas e horas no fuso do negócio.

## Fora de escopo no MVP
Microsserviços, mensageria, multiunidade, pagamentos, notificações, tempo real.
