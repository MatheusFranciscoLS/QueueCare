# Regras de negócio do QueueCare

Estas regras são o coração do projeto. A implementação e os testes de cada regra são feitos por Claude e revisados pelo ChatGPT (ver `CLAUDE.md`, seção 2). Mudou uma regra? Atualize este arquivo e, se for decisão de arquitetura, crie um ADR.

## R1. Disponibilidade
Um agendamento só pode ocorrer dentro do horário de atendimento do prestador (`availability_windows`) e deve caber inteiro, respeitando a duração do serviço.
Exemplo: serviço de 60 min não pode começar às 17h30 se o expediente termina às 18h.
Detalhes definidos na implementação (`AvailabilityPolicy`, ver `docs/estudo/r1-disponibilidade.md`): início e fim no mesmo dia local do prestador; janelas coladas contam como expediente contínuo e qualquer lacuna interrompe; janelas sobrepostas são configuração inválida.
Teste: T2 (fora do expediente).

## R2. Sem reserva dupla e sem sobreposição
Dois agendamentos ativos do mesmo prestador não podem se sobrepor, nem sob requisições simultâneas, nem com serviços de durações diferentes (ex.: 60 min às 10h e 30 min às 10h30).
Proteção em duas camadas: validação na aplicação (transação) **e** garantia no PostgreSQL (ADR 0002). Conflito devolve **HTTP 409**.
Cancelados não ocupam a agenda. Horários consecutivos são permitidos (intervalo `[início, fim)`).
Teste: T3 (concorrência com PostgreSQL real, duas threads, uma vence).

## R3. Cancelamento e liberação
O cliente só cancela dentro do prazo definido pelo prestador. Ao cancelar, o horário volta a ficar livre e o evento é registrado em `appointment_events`.
Detalhes definidos na implementação (`CancellationPolicy`, `CancellationService`, ver `docs/estudo/api-agendamento.md`): o cliente cancela só o próprio e até o prazo do prestador (exatamente no limite ainda pode); o prestador dono cancela sem prazo; agendamento de outra pessoa responde 404.
Teste: T4.

## R4. Autorização por perfil (na API)
- Cliente: consulta e cancela só os próprios agendamentos.
- Atendente: administra a agenda do prestador.
- Prestador: configura serviços e horários.
Não basta esconder botões no React; a API valida.
Teste: T5.

## R5. Fuso e antecedência
Rejeitar agendamento no passado e respeitar a antecedência mínima configurada. Guardar `timestamptz` (UTC); o prestador tem fuso IANA (ex.: `America/Sao_Paulo`). Tratar horário local inexistente ou ambíguo (mudança de horário de verão).
Observação: o Brasil não tem horário de verão desde 2019, então `America/Sao_Paulo` não sofre esse problema hoje; a regra continua valendo porque o fuso é configurável por prestador (ex.: `America/New_York`) e os testes T6 devem usar um fuso que tenha mudança de horário.
Detalhes definidos na implementação (`BookingTimeRules` e `LocalTimeResolver`, ver `docs/estudo/r5-fuso-antecedencia.md`): o início conta contra o momento atual e a antecedência mínima (igual ao limite é permitido); horário local inexistente ou ambíguo não é adivinhado: é devolvido como tal e a API decide.
Teste: T6.

## Testes que precisam existir
| ID | Prova | Ferramenta |
|---|---|---|
| T1 | Agendamento válido dentro da disponibilidade | JUnit |
| T2 | Rejeita fora do expediente | JUnit |
| T3 | Concorrência: duas reservas simultâneas, uma vence | Testcontainers + PostgreSQL |
| T4 | Cancelamento libera o horário e registra o evento | MockMvc + PostgreSQL |
| T5 | Cliente não altera agendamento de outro | MockMvc |
| T6 | Fuso e antecedência | JUnit |

`@Version` (bloqueio otimista) pode ajudar em atualizações concorrentes, mas **não** substitui a garantia de exclusividade do horário no banco.
