# Estudo: configuração do prestador e horários livres (PR 14)

## Em linguagem simples
Agora o sistema não depende mais de dados "plantados" no banco: o **prestador configura o próprio negócio pela API** e o **cliente consulta os horários livres** antes de agendar.

| Chamada | Quem pode | O que faz |
|---|---|---|
| `POST/GET/PUT /api/provider` | PRESTADOR | cadastra, consulta e edita o próprio negócio: nome, **fuso IANA**, antecedência mínima e prazo de cancelamento |
| `GET/POST /api/provider/services`, `PUT /api/provider/services/{id}` | PRESTADOR | lista, cria e edita (inclusive desativa) os próprios serviços |
| `GET/PUT /api/provider/availability` | PRESTADOR | consulta e **substitui** as janelas semanais de atendimento |
| `GET /api/providers/{id}/slots?serviceId=&date=` | qualquer usuário logado | horários livres de um serviço num dia (data local do prestador) |

**Isolamento:** o negócio é sempre o do **usuário do token**. Nenhum id de prestador vem do cliente nas rotas de configuração, então um prestador nunca enxerga nem altera o de outro (serviço de outro responde 404, igual a "não existe").

## Como os horários livres são calculados
Para cada janela do dia da semana, a cada **15 minutos**, o sistema testa um início possível e só o oferece se passar por **todas** as regras que já existem:
1. **R5:** o horário local existe e não é ambíguo (horário de verão) e respeita passado e antecedência mínima;
2. **R1:** o serviço cabe inteiro no expediente (e não atravessa o almoço);
3. **R2:** não se sobrepõe a nenhum agendamento ativo (cancelados liberam o horário).

Importante: é uma **consulta**. Um horário listado pode ser tomado por outra pessoa antes de você clicar; quem decide é a reserva (`BookingService`) e a trava do banco (ADR 0002), que responde 409 nesse caso. O teste "todo horário listado pode ser agendado" prova que a lista e a reserva concordam.

## Validações
Nome 1 a 160 caracteres; fuso IANA válido; antecedência e prazo de 0 a 7 dias (em minutos); serviço de 5 a 480 minutos; no máximo 70 janelas; janelas do mesmo dia não podem se sobrepor (coladas valem). Valores inválidos: **400** com o campo e a mensagem. Cadastrar o negócio duas vezes: **409**.

## O que os testes provam (HTTP real + PostgreSQL real, 12 testes)
Cadastro/consulta/edição do negócio, 400 de valores inválidos, 403/401 por perfil, serviços (criar, editar, desativar, isolamento entre prestadores), janelas (substituir, sobreposição recusada sem alterar o que existia, isolamento), grade de 15 min (26 horários para 08-12 e 14-18 com serviço de 60 min), horário reservado sai da lista e volta ao cancelar, passado, limite de 90 dias e antecedência, 404/401/400 da consulta e serviço desativado, e que os horários listados podem ser agendados.

## Onde está
`backend/src/main/java/com/queuecare/scheduling/`: `application/ProviderAdminService`, `application/SlotService`, `application/ProviderExceptions`, `web/ProviderController`, `web/SlotController`.

## 3 perguntas de entrevista (responda com as suas palavras)
1. Por que as rotas de configuração não recebem o id do prestador, e o que isso evita?
2. Por que a lista de horários livres não garante que o horário ainda estará livre na hora de reservar, e o que garante a exclusividade?
3. Por que desativar um serviço em vez de apagá-lo?
   *Pista: agendamentos antigos apontam para ele (chave estrangeira restritiva).*

## Limitações e o que ainda falta
- Atendente ainda sem vínculo com o prestador; cadastro de PROVIDER por API pública ainda não existe (o usuário PROVIDER precisa ser criado por outro caminho).
- Grade fixa de 15 minutos; sem exceções de data (feriados) nem folgas; sem paginação em listas do prestador.
- A consulta usa o relógio do servidor e não tem cache.
