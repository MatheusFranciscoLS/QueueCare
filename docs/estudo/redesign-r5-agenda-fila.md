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

## Revisão do ChatGPT (2 MEDIUM, 1 LOW) e o que mudou
1. **Ordem de leitura (MEDIUM):** a ordem visual dependia de `order` no CSS, e leitores de tela leem a ordem do DOM. Agora **o DOM está na ordem visual** (hora, pet, tutor, serviço, observações, estado, ações) e todos os `order` saíram. Teste confere a sequência no DOM, inclusive que o estado vem antes das ações. Na Fila o mesmo (a posição, o pet, o tutor, o serviço...).
2. **O fio completo conforme o ADR (MEDIUM):** o concluído agora tem a **marca de verificação** sobre o ponto cheio; entrou o marcador **"agora"** (etiqueta dourada com a hora e linha dourada) entre o último atendimento que já começou e o próximo, **só no dia de hoje e só quando há um atendimento antes dele**. A comparação é feita por **instantes** (não depende do fuso do navegador) e o rótulo mostra a hora **no fuso do negócio**; o relógio anda a cada minuto e ao voltar à aba. O marcador mora dentro do `li` do próximo atendimento (sem criar item de lista novo), então a contagem de itens e a acessibilidade da lista não mudam. Testes com o relógio fixado: aparece antes do próximo com "agora 10:42" (10:42 em São Paulo), some depois do último, e some com a lista vazia ou sem atendimento antes dele. Capturado em Edge com o relógio em 10:00 (a linha dourada fica entre as linhas, sem sobrepor texto).
3. **Fila (LOW):** a Fila agora mostra **horário marcado** e **tempo de espera** ("marcado para 09:30 · espera de 13 h 28"; testes do formato). A separação "Em atendimento / Esperando" e "Começar atendimento" **dependem de um estado novo** (hoje o atendimento vai de "chegou" a "concluído"): é mudança de domínio, **fora do redesign**; a delimitação foi escrita no ADR (seção 5) para um ADR próprio.

Testes: 373 (14 novos: ordem no DOM, marcador, fila com espera, `waitLabel`). Edge, modo estrito, Agenda do prestador, Agenda do atendente e Fila (20 combinações): axe 0, foco 0, rolagem 0, alvos 0 (um achado de foco na Fila no celular, o texto "marcado para..." caindo na coluna errada, foi encontrado e corrigido nesta rodada).
