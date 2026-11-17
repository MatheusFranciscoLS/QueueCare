# Redesign R5: Agenda do dia e Fila (estudo)

Parte do ADR 0018 v2. É a tela principal do produto: aqui o **fio do dia** aparece com dados reais.

## O que muda
- **Agenda do dia (prestador e atendente):** cada agendamento vira uma linha do fio: **hora grande** (Bricolage), **ponto do estado** (anel = agendado, meio = chegou, cheio = concluído), **pet em destaque** (nome, espécie, raça, porte), tutor, serviço, observações, rótulo do estado e ações abaixo. O fio liga os pontos: **pontilhado** enquanto falta, **cheio** no trecho concluído. Sem pet gravado (agendamentos antigos), o tutor vira o destaque.
- **Resumo do dia em frase** acima da lista: "1 concluído, 2 esperando e 2 por vir." (acompanha a chegada registrada na hora; sem agendamentos mostra só o aviso do dia vazio).
- **Fila sem o fio** (ordem de chegada, não sequência de horários): a **posição** é o número grande, sem ponto e sem linha; o pet em destaque; "chegou às 09:20" e "Concluir atendimento".
- A barra do dia ganhou folga entre o texto "Data" e o campo de data (o anel de foco do campo se misturava ao texto).

## Como, sem tocar no comportamento
A marcação das linhas **não mudou**: o `.appt-info` virou `display: contents` e seus filhos entram direto na grade da linha (hora na coluna 1, ponto e fio nas pseudo-marcas, conteúdo na coluna 3, com `order` para a hierarquia). Toda a lógica (chegada, conclusão, remarcação, cancelamento com confirmação na linha, foco, histórico) é a mesma e continua coberta pelos testes existentes. Só entram o componente `Summary` e o cálculo das contagens.

## Evidências
- 361 testes (2 novos: contagem do resumo no singular e no plural e acompanhando a chegada; dia vazio sem resumo). Os 359 anteriores passam sem alteração. Mutante (contar "chegou" como concluído) é morto.
- Edge, modo estrito, Agenda do prestador, Agenda do atendente e Fila, claro e escuro, 1280 e 390 px (20 combinações): axe 0, foco 0, rolagem 0, alvos 0 (a Agenda deixa de ter o item pendente do R3: o campo de data).
