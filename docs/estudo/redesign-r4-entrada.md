# Redesign R4: entrada e início (estudo)

Parte do ADR 0018 v2. O desenho passa do esqueleto (R3) para o miolo das primeiras telas: **Entrar, Cadastrar, Trocar senha, Página não encontrada e Início** (prestador, atendente, cliente, administrador).

## O que muda (sem tocar na lógica das telas)
- **Estilos globais do sistema antigo reescritos com os tokens novos:** cartão sem moldura (estrutura por espaço, tipografia e fio pontilhado), títulos em Bricolage (h1 até 2,75 rem), campos com contorno de 2 px, 10 px de raio e 48 px de altura, botões de 44 x 44 px, avisos com contorno, erros com ícone de exclamação e texto (nunca só a cor), links de texto soltos com área de toque de 44 px.
- **Início:** o resumo do dia deixa de ser três caixas e vira **uma frase** com os números em destaque ("1 agendado, 1 na fila e 1 concluído."), com o Próximo e os atalhos abaixo (a primeira versão do PR usava três números com o ponto do estado; foi trocada na revisão para seguir o ADR); os atalhos viram uma lista separada por fio pontilhado, com o nome em Bricolage e a descrição em texto de apoio.
- **Página não encontrada:** frase e ação ("Ir para o início").
- **Atalho "Pular para o conteúdo"** passa a ser aceito pelo verificador de foco como indicador próprio (o elemento só aparece ao receber o foco); a barra lateral ganhou folga lateral para o anel de foco não ser cortado.

## Evidências
- 359 testes passam. A marcação das telas não mudou, **exceto o resumo do dia** (frase no lugar de lista de definições), e por isso os testes de `today-summary.test.tsx` foram ajustados para verificar a frase. Verificação estrita em Edge, claro e escuro, 1280 e 390 px, nas 6 telas (Entrar, Cadastrar, Trocar senha, Não encontrada, Início do cliente e do prestador): axe 0, foco 0, rolagem 0, alvos 0.
- O verificador ganhou o caso do elemento que só aparece com o foco (teste próprio).

## Revisão do ChatGPT (2 MEDIUM) e o que mudou
1. **Estilo global do `.card` e dos campos atingindo telas ainda não migradas:** a mudança é **intencional e registrada**: a ponte de variáveis do R3 já era global (as telas antigas já usavam as cores e as fontes novas), e manter a moldura antiga só em parte das telas deixaria o produto inconsistente (telas com e sem moldura lado a lado). Para não ser uma regressão colateral implícita, foi **validada**: verificação estrita em Edge nas **17 telas**, claro e escuro, 1280 e 390 px: **axe 0 e rolagem horizontal 0 em todas as 68 combinações**; falham só 5 telas **por itens do conteúdo antigo que já falhavam antes e continuam a cargo da migração delas** (campos de data da Agenda, campos de horário do Expediente, caixa de marcar de Pets). Capturas de Serviços, Agendar, Fila e Contas conferidas (sem perda de estrutura).
2. **Resumo do dia como frase (ADR seção 3):** o resumo agora é **uma frase** com os números em destaque, em vez de três blocos ("1 agendado, 1 na fila e 1 concluído."), com o "Próximo" e os atalhos logo abaixo; o CSS dos blocos saiu. Testes ajustados à frase (359 passam).

## Composição da entrada refeita (a pedido do dono)
A primeira versão da entrada (R4) só restilizava os campos e deixava o formulário solto no canto; a segunda trocou isso por um painel dividido com um "dia de exemplo" (o fio do dia) e três bolinhas na marca, e o dono rejeitou: parecia cópia, o cronograma não tinha a ver com banho e tosa e as bolinhas não significavam nada fora da Agenda. A terceira, esta, foi desenhada com a skill de design:
- **Painel de marca do universo do banho:** fundo cor de água, promessa em Bricolage ("Banho e tosa na hora certa.") e uma ilustração própria em formas simples (cachorro dourado com espuma na cabeça e bolhas; o damasco é o dourado da marca), decorativa e escondida de leitores de tela. Sem degradê, sem diagrama.
- **Marca sem as três bolinhas** (no menu lateral e na gaveta também): os pontos de estado só aparecem onde há um estado a mostrar.
- **Formulário:** cartão com título, uma frase de ação ("Entre para ver a agenda e a fila de hoje."), "Mostrar senha" com estado dito a leitores de tela; a página não encontrada de quem não entrou usa a mesma composição.
- Folga entre o texto e os links em linha (o anel de foco não se mistura ao texto) e anel de foco próprio no painel claro (o anel do tema escuro não tinha contraste sobre o fundo claro).
- Testes: 378 (composição, marca como link, ilustração decorativa, "Mostrar senha" nos dois sentidos). Edge, modo estrito: Entrar, Cadastrar e Não encontrada, claro e escuro, 1280 e 390 px: axe 0, foco 0, rolagem 0, alvos 0.

## Segunda opinião do ChatGPT sobre a ilustração e o painel (a pedido do dono)
- **Ilustração:** "no caminho certo, ainda pende um pouco para o infantil". Aplicado: cabeça e olhos menores, menos bolhas (de nove para quatro), espuma e banheira em tons menos brancos, formas mais contidas, dourado do pelo preservado. Descrita como "cachorro dourado", sem prometer raça.
- **Painel no tema escuro:** o ChatGPT recomendou uma **variante escura própria** (e não manter o painel claro nos dois temas, que era a minha proposta): azul-petróleo profundo, azulejo de baixo contraste, espuma sem branco estourado, dourado do pelo igual. Adotado; a água, a espuma e a banheira da ilustração vêm de variáveis do painel (claro e escuro).
