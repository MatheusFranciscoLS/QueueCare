# ADR 0035: reserva idempotente (repetir o mesmo pedido não vira conflito)

- **Status:** Accepted (implementado em 24/11/2026)
- **Data:** 24/11/2026
- **Esforço:** BAIXO (uma consulta, um trecho do serviço de reserva, 4 testes)

## Contexto: a análise crítica
Cenário realista, que os testes de sucesso e de erro comuns não pegam: o tutor envia a reserva, o servidor **grava com sucesso**, mas a resposta demora ou se perde (rede de celular, tempo esgotado). A tela mostra erro, o tutor toca de novo, e o segundo pedido chega a um horário que já está ocupado, **pela própria reserva dele**. A resposta era `409 SLOT_TAKEN`, que a tela traduz como "esse horário acabou de ser ocupado por outra pessoa": falso, e confuso para quem acabou de reservar.

Isso é mais provável aqui do que num ambiente comum: a API do Render gratuito dorme e leva cerca de um minuto para acordar, e a primeira chamada depois do sono é justamente a que mais estoura o tempo. O bloqueio contra duplo envio da tela (um clique por vez) não ajuda quando o segundo pedido vem de uma nova tentativa, depois do erro.

## Decisão
1. **O mesmo pedido, repetido, devolve a reserva que já existe.** Quando o horário está ocupado, o serviço de reserva confere se o ocupante é **a mesma reserva**: mesmo estúdio, mesmo tutor, mesmo serviço, mesmo pet e mesmo instante de início, e ainda ativa. Se for, devolve essa reserva (a mesma resposta do primeiro pedido, `201`), sem criar nada e sem novo evento no histórico.
2. **Qualquer diferença continua sendo conflito.** Outro tutor, outro pet (mesmo do mesmo tutor) ou outro horário ocupado dão `409`. A trava do banco (restrição de exclusão, ADR 0002) **não mudou**: o que muda é só a interpretação de quem bate nela.
3. **Reserva cancelada ou com falta não serve de resposta**: já libera o horário (ADR 0021), então o mesmo pedido depois disso cria uma reserva nova.
4. **Vale para os dois caminhos**, pelo tutor e pela equipe no balcão (ADR 0033), porque a regra mora no serviço de reserva.
5. **Cobre também o envio duplo simultâneo**: dois pedidos idênticos ao mesmo tempo, um grava e o outro bate na trava do banco; ao falhar, o segundo procura a reserva idêntica, já confirmada, e devolve a mesma (há teste com duas threads).

## O que foi descartado
- **Cabeçalho `Idempotency-Key`**: é o padrão da indústria para pagamentos, mas exige que o cliente gere e reenvie a chave, que o servidor a guarde com prazo de validade e que se trate chave reutilizada com corpo diferente. Para uma reserva, o **próprio conteúdo do pedido já identifica a intenção** (quem, o quê, quando), então a chave seria cerimônia e uma tabela a mais. Se algum dia houver operação sem identidade natural (por exemplo, pagamento), a chave volta a fazer sentido.
- **Tratar o `409` na tela** (conferir "Minhas reservas" antes de mostrar o erro): esconde o problema no cliente, não ajuda a equipe pelo balcão e deixa a API enganosa para qualquer outro cliente.

## Riscos conhecidos
- **Devolver `201` em vez de `200` na repetição** é um atalho: o cliente trata os dois como sucesso, e nada na API distingue "criei agora" de "já existia". Se isso precisar ser distinguido, é um cabeçalho de resposta, sem mudar o corpo.
- **Pedido igual de propósito** (duas reservas idênticas) não é possível mesmo: o banco impede dois ocupantes do mesmo horário, então a repetição nunca poderia criar outra.

## Consequências
- Uma resposta perdida ou uma API acordando não gera mais um erro enganoso para quem acabou de reservar com sucesso.
- Nenhuma tabela nova, nenhuma migração, nenhuma mudança de contrato (o contrato OpenAPI não muda).
