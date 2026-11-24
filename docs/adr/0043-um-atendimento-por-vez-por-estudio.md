# ADR 0043: um atendimento por vez por estúdio (limitação conhecida)

- **Status:** Accepted (documenta o que o sistema faz desde o ADR 0002; nenhuma mudança de comportamento)
- **Data:** 24/11/2026
- **Esforço:** BAIXO (só documentação)

## Contexto
A garantia central do agendamento é uma restrição do PostgreSQL (ADR 0002, migração V1, refeita na V22 para liberar o horário de quem faltou):

```sql
CONSTRAINT appointments_no_overlap EXCLUDE USING gist (
    provider_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
) WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'))
```

Ou seja: dois agendamentos ativos **do mesmo estúdio** (`provider_id`) não podem ter intervalos que se sobreponham, mesmo sob requisições simultâneas. O estúdio é a unidade de capacidade: **cabe um atendimento por vez**. A equipe (`provider_staff`) existe para operar a agenda (chegada, atendimento, balcão), não para somar capacidade.

Isto foi visto em 24/11/2026, ao revisar o que um estúdio real pediria na primeira semana de uso: **um estúdio com dois tosadores não consegue marcar dois pets no mesmo horário**; a segunda reserva responde conflito de horário.

## Decisão
**Manter a regra como está e registrá-la como limitação conhecida.** Não é um defeito da trava de concorrência (ela protege corretamente a regra que o modelo implementa); é que a regra é mais restritiva do que um estúdio com mais de um profissional precisaria. O modelo atual representa bem o estúdio **de um profissional atendendo por vez**, que é o caso do estúdio de demonstração e de boa parte dos estúdios pequenos.

Por que não mudar agora:
- **Não é só acrescentar uma coluna à restrição.** A exclusão por `provider_id` e intervalo teria de passar a considerar o **recurso** que atende (profissional ou "vaga" de 1 a N). Isso arrasta: a associação do agendamento ao recurso; a disponibilidade, as folgas e as férias por profissional; os serviços que cada um faz; reservas em que o tutor não escolhe o profissional (quem recebe a vaga?); a **concorrência na atribuição** da vaga (duas reservas pedindo a última vaga livre); o cálculo de horários livres (`SlotService`), a remarcação, a falta e o balcão; e a migração dos agendamentos existentes.
- É uma mudança **no coração do agendamento**, a parte mais testada e mais crítica do sistema (ADR 0002, ADR 0035). Fazê-la às pressas, só para eliminar uma limitação, troca uma garantia provada por uma não provada.
- Nenhum estúdio real usa este sistema; a decisão de ampliar o domínio é de produto, não técnica, e fica com o dono do projeto.

## Direção futura (se houver decisão de ampliar)
1. **Vagas numeradas por estúdio** (campo `lane` de 1 a N em `providers`): a restrição passa a `(provider_id, lane, intervalo)`. A reserva escolhe a primeira vaga livre dentro de uma transação que serializa por estúdio (a mesma técnica do bloqueio de linha que o projeto já usa). É a menor mudança que dá capacidade N, sem modelar pessoas.
2. **Profissionais como recurso** (`staff_id` no agendamento, disponibilidade individual): modelo mais fiel, bem maior.

Qualquer um deles começa por um ADR novo que substitui este, com testes de concorrência escritos **antes** da mudança (duas reservas simultâneas disputando a última vaga).

## Consequências
- O contrato e a tela dizem a verdade: um horário ocupado é um horário ocupado para todo o estúdio.
- A limitação aparece na documentação e nas entrevistas como decisão consciente, não como descuido.
