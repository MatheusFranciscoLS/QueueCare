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
(Será preenchido ao fim do marco M1.)

## Autoria
Projeto individual de Matheus Francisco, desenvolvido com apoio de IA para estrutura, revisão e documentação; as regras de negócio centrais, a autorização e os testes críticos são escritos e explicados por ele (ver [`CLAUDE.md`](CLAUDE.md)).
