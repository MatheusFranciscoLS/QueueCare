# Redesign R4: entrada e início (estudo)

Parte do ADR 0018 v2. O desenho passa do esqueleto (R3) para o miolo das primeiras telas: **Entrar, Cadastrar, Trocar senha, Página não encontrada e Início** (prestador, atendente, cliente, administrador).

## O que muda (sem tocar na lógica das telas)
- **Estilos globais do sistema antigo reescritos com os tokens novos:** cartão sem moldura (estrutura por espaço, tipografia e fio pontilhado), títulos em Bricolage (h1 até 2,75 rem), campos com contorno de 2 px, 10 px de raio e 48 px de altura, botões de 44 x 44 px, avisos com contorno, erros com ícone de exclamação e texto (nunca só a cor), links de texto soltos com área de toque de 44 px.
- **Início:** o resumo do dia deixa de ser três caixas e vira três números com o ponto do estado (anel = agendados, meio = na fila, cheio = concluídos); os atalhos viram uma lista separada por fio pontilhado, com o nome em Bricolage e a descrição em texto de apoio.
- **Página não encontrada:** frase e ação ("Ir para o início").
- **Atalho "Pular para o conteúdo"** passa a ser aceito pelo verificador de foco como indicador próprio (o elemento só aparece ao receber o foco); a barra lateral ganhou folga lateral para o anel de foco não ser cortado.

## Evidências
- 359 testes passam sem alteração (a marcação das telas não mudou). Verificação estrita em Edge, claro e escuro, 1280 e 390 px, nas 6 telas (Entrar, Cadastrar, Trocar senha, Não encontrada, Início do cliente e do prestador): axe 0, foco 0, rolagem 0, alvos 0.
- O verificador ganhou o caso do elemento que só aparece com o foco (teste próprio).
