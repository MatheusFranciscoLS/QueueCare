# Estudo: serviço de agendamento (PR 10)

## Em linguagem simples
Até aqui existiam as regras isoladas (R1, R2, R5) e o banco. Este PR **liga tudo**: o `BookingService` recebe um pedido ("cliente X quer o serviço Y do prestador Z, dia 17/11 às 10:00 no horário do prestador") e executa, em ordem:

1. Confere que prestador, serviço (do mesmo prestador e ativo) e cliente (ativo) existem.
2. **R5:** converte o horário local em instante UTC no fuso do prestador (horário que não existe ou que se repete no horário de verão é recusado) e calcula o fim pela **duração do serviço**.
3. **R5:** recusa passado e respeita a antecedência mínima.
4. **R1:** confere se cabe inteiro no expediente.
5. **R2 (aplicação):** olha se já existe agendamento ativo que se sobreponha, só para dar uma mensagem clara.
6. **Grava** o agendamento e o evento "agendado" numa transação.

**A parte mais importante:** o passo 5 não impede reserva dupla sob concorrência (duas requisições podem passar juntas). Quem garante é a **trava do banco** (ADR 0002). Se o banco recusar, o serviço traduz o erro para `BookingConflictException` (a futura API responderá **HTTP 409**), mas **só** quando o erro é o conflito de horário (`23P01` da restrição `appointments_no_overlap` ou `40P01` deadlock). Uma chave estrangeira inválida, por exemplo, **não** vira 409. A tradução acontece **depois** do rollback da transação que falhou.

## O que os testes provam (Spring completo + PostgreSQL 16 real)
- Esquema do banco confere com as entidades (`ddl-auto: validate`).
- Caminho feliz, consecutivos aceitos, conflito (igual e parcial), cancelado libera, outro prestador, T2, almoço, passado, antecedência (limite exato aceito), horário de verão (inexistente e ambíguo em Nova York), UTC correto, dados inválidos, que um pedido recusado não grava nada e que uma falha ao gravar o evento desfaz também o agendamento (atomicidade).
- **Concorrência pelo serviço:** 2 e 8 requisições simultâneas ao mesmo horário: uma vence, as outras recebem só `BookingConflictException`, e o banco fica com uma reserva (repetido 20 rodadas).
- O detector de conflito, com erros reais do PostgreSQL passando por Spring e Hibernate.

## Um defeito real que os testes pegaram
A configuração `hibernate.jdbc.time_zone: UTC` deslocava as colunas `time` das janelas de atendimento (00:00-06:00 virava 21:00-03:00 em São Paulo). Foi removida; o resultado agora não depende do fuso da máquina (testado com a JVM em Tóquio, UTC e São Paulo).

## Onde está
- `backend/src/main/java/com/queuecare/scheduling/application/`: `BookingService`, `BookingRequest`, `BookingRejectedException`, `BookingConflictException`, `SchedulingConflictDetector`, `ClockConfig`
- `backend/src/main/java/com/queuecare/scheduling/persistence/`: entidades JPA e repositórios
- Testes: `BookingServiceIntegrationTest` (22), `BookingConcurrencyIntegrationTest` (2), `SchedulingConflictDetectorIntegrationTest` (4)

## 3 perguntas de entrevista (responda com as suas palavras)
1. Por que o serviço faz uma checagem de sobreposição antes de gravar se o banco já garante a exclusividade?
   *Pista: mensagem clara versus garantia final.*
2. Por que o erro do banco só é traduzido para 409 depois do rollback, e por que não se captura qualquer erro de integridade como conflito?
   *Pista: transação abortada no PostgreSQL; chave estrangeira inválida não é conflito de horário.*
3. O que acontece se o cliente escolher um horário local que não existe no horário de verão, e por que o serviço não escolhe outro sozinho?

## Limitações e o que ainda falta
- Ainda **não há API HTTP, autenticação nem autorização** (R4, ADR 0003): por isso este PR entrega o serviço, e o 409 existe como exceção de domínio.
- Falta o cancelamento (R3) e a conferência do perfil do usuário (R4).
- O relógio vem de um `Clock` injetado (UTC); nos testes é fixo.
