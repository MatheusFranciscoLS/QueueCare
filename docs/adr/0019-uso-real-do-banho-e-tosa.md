# ADR 0019: do agendador ao uso real do banho e tosa (contato, ficha do pet, preço e atendimento)

- **Status:** Accepted (o Matheus aprovou a ordem em 20/11/2026: "pode seguir com a ordem recomendada"; o ChatGPT está fora do ar por falha da extensão do Chrome e o Matheus autorizou seguir sem ele por enquanto; a revisão dele entra assim que voltar)
- **Data:** 20/11/2026
- **Esforço:** ALTO (muda domínio e banco; cada fatia abaixo é um PR separado)
- **Meta de produto (palavras do dono):** uma aplicação **real**, usada **todos os dias** pelo estúdio e pelos tutores, no mesmo espírito do Vardão Central.

## Contexto: a análise crítica

Percorrendo o sistema como **tutor que cadastra o cachorro** e como **profissional de banho e tosa**, o visual já está coerente (ADR 0018), mas o produto ainda é uma agenda genérica. O que um estúdio usa de verdade e não existe:

| Falta | Por que importa no balcão |
|---|---|
| Telefone/WhatsApp do tutor | O estúdio avisa "o Thor está pronto" por WhatsApp; hoje só há e-mail. |
| Ficha do pet (pelo, peso, comportamento, saúde, vacina) | O tipo de pelo e o comportamento mudam o atendimento; alergia e medo de secador precisam estar na frente de quem atende. |
| Preço do serviço | O tutor precisa saber quanto vai pagar antes de reservar; o estúdio precisa ver o valor do dia. |
| Estados de atendimento ("em atendimento", "pronto para retirada") | Hoje só existe "chegou" e "concluído": o tutor não sabe quando buscar. |
| Início como painel | Hoje é uma lista de atalhos; o dia a dia pede "o que importa agora". |
| Histórico do pet e retorno periódico | "Quando foi o último banho?" é a pergunta mais comum do balcão. |

## Decisão

Seis fatias, **cada uma um PR** (backend + telas juntos, testes leves por PR; a verificação de ponta a ponta fica para o fim do conjunto, por decisão do dono para não gastar recurso à toa):

1. **Telefone do tutor.** Coluna opcional `users.phone`, só dígitos com DDI (`55` + DDD + número, 12 ou 13 dígitos; o banco recusa o resto com `CHECK`). Aceita o que o tutor digitar com máscara ou sem DDI; a API normaliza para o formato único. Cadastro de cliente pede o telefone (opcional no banco porque contas antigas não têm); o cliente edita em "Meu perfil"; a **equipe só vê o telefone de quem tem agendamento no seu prestador** (agenda do dia e fila), nunca por busca. A tela oferece o link `wa.me` (custo zero, sem integração paga). O telefone é dado pessoal: não aparece em log, em resposta de erro nem na agenda da semana.
2. **Ficha do pet.** Campos opcionais novos em `pets`: `coat` (`SHORT`, `MEDIUM`, `LONG`, `CURLY`, `DOUBLE`), `weight_kg` (`numeric(4,1)`, de 0,5 a 120,0), `temperament` (`CALM`, `ANXIOUS`, `REACTIVE`), `health_notes` (até 300 caracteres, alergias e condições) e `vaccinated` (booleano; nulo = não informado). Entram no **snapshot do agendamento** (ADR 0016): o estúdio vê a ficha do dia da reserva, com a mesma regra de atualização enquanto `SCHEDULED`. O comportamento `REACTIVE` vira um aviso visível na agenda e na fila. Mantém as mesmas regras de normalização e `CHECK` do ADR 0016.
3. **Preço do serviço.** `services.price_cents` (base, nulo = "a combinar"), `price_medium_cents` e `price_large_cents` (nulos = usa o base). O valor do agendamento é **congelado** em `appointments.price_cents` na reserva, calculado pelo porte do pet (`SMALL` ou sem porte = base; `MEDIUM`; `LARGE`), então mudar o preço depois não reescreve o histórico. O tutor vê o valor antes de confirmar; o estúdio vê na agenda do dia. **Não há pagamento online** (fora de escopo; o valor é informativo). Dinheiro sempre em centavos inteiros, nunca ponto flutuante.
4. **Estados de atendimento.** A máquina do ADR 0011 passa de `SCHEDULED → CHECKED_IN → COMPLETED` para `SCHEDULED → CHECKED_IN → IN_PROGRESS → READY → COMPLETED` (retirado). Só para frente; cada transição grava evento com quem fez, como hoje (UNIQUE por tipo continua valendo). A fila mostra "esperando", "em atendimento" e "pronto"; **"pronto" abre o link de WhatsApp** com mensagem pronta para o tutor. Cancelamento continua só antes de chegar.
5. **Início como painel.** Cliente: próximo agendamento (com o pet), atalho "Agendar" e seus pets; estúdio: o dia (a fila e o próximo), pendências de configuração só enquanto faltarem. Sem dado novo no backend além do que as fatias 1 a 4 trazem.
6. **Histórico do pet e retorno.** Tela "histórico" do pet (atendimentos concluídos com serviço, data e valor) e sugestão "agendar de novo" com o mesmo serviço. **Lembrete de retorno por WhatsApp fica fora do escopo** (precisa de envio automático; só reavaliar com custo zero comprovado).

## Fora de escopo (decisões conscientes)
- **Duração por porte/pelo** (um golden demora mais que um poodle): exige escolher o pet **antes** dos horários e recalcular os horários livres por pet. É a próxima melhoria de maior valor depois destas seis; vira ADR próprio.
- Pagamento online, fotos do pet, prontuário clínico, notificação automática (SMS, e-mail ou WhatsApp API), pacotes e assinaturas.

## Regras que valem para todas as fatias
- Dado novo é opcional no banco (contas, pets e serviços existentes continuam válidos) e **obrigatório só onde a tela o exige** (por exemplo, telefone no cadastro novo).
- A aplicação normaliza e o banco garante o resultado (como no ADR 0016); mensagens de erro nunca repetem o valor recebido.
- Autorização na API, não na interface: o que a equipe vê do tutor e do pet vem do agendamento em que opera; outro prestador, outro cliente e ADMIN não recebem telefone nem ficha.
- Cada PR atualiza o contrato OpenAPI (ADR 0012) e os testes de autorização do campo novo.

## Consequências
- O produto passa de "marcar horário" para "tocar o dia do estúdio", que é o que sustenta uso diário.
- Mais colunas no snapshot do agendamento (ADR 0016) e mais um estado na fila; o custo é a migração de cada fatia e a revisão do contrato.
- Sem integração paga: o aviso ao tutor é um link `wa.me` aberto pela equipe, não um envio automático.
