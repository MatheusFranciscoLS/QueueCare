# ADR 0018: redesign completo (Pelo & Ponto)

- **Status:** Proposed (v2). A v1 foi aprovada (ChatGPT: APPROVE no commit 48afaa5) e o R1 já está mesclado. **A direção visual (seções 1, 2, 3 e as telas da seção 5) foi refeita** depois de a v1 ser reconhecida como a "paleta e a composição padrão" (verde-sálvia com coral, cartões iguais, Archivo e DM Sans), sem ter passado pela skill de design. A v2 foi desenhada com ela, **mostrada ao dono em uma prévia navegável** (Agenda dia e semana, Fila, formulário, gaveta, claro e escuro) e **avaliada pelo ChatGPT** (a prévia está em `docs/redesign/previa-fio-do-dia.html`, página estática com dados fictícios; abre no navegador), que a considerou coerente e sem bloqueio de design. O que **não** mudou da v1: regras que não mudam, navegação por perfil, acessibilidade, critérios de verificação, ambiente local e plano. Observação do revisor para o R1, já cumprida: registrar o tamanho real transferido por rota.
- **Data:** 16/11/2026 (v2: 16/11/2026, mesma data, após a prévia)
- **Esforço:** ALTO (muda toda a apresentação, a navegação e a forma de verificar o produto)
- **Contexto:** o produto funciona e tem a lógica bem protegida (581 testes de backend e 325 de front, com mutação), mas o visual nasceu como "sistema interno": um cabeçalho com menu em linha, cartões iguais, identidade genérica (ADR 0014, verde-petróleo, Inter e Manrope) e **nenhuma tela jamais foi verificada em navegador real** (só jsdom). O dono quer um produto **profissional, moderno e atual** para o nicho aprovado (banho e tosa), no nível do portfólio, sem cara de gerado por IA.
- **Ordem decidida** (Matheus e ChatGPT concordam): **este ADR, depois o redesign em PRs pequenos, cada um com verificação em navegador real, e só depois uma suíte E2E enxuta.** Escrever uma suíte E2E extensa agora seria retrabalho (as telas e seletores vão mudar); deixar toda a verificação de navegador para o final seria arriscado (foco, celular e contraste só aparecem em navegador real).

## Revisão v2.1 (17/11/2026): o que o dono rejeitou e o que passou a valer
Depois de ver as primeiras telas no ar, o dono rejeitou três coisas, e este ADR passou a dizer o contrário do que dizia: **(1)** a tela de entrada solta no canto, depois com cronograma e três bolinhas (viraram painel de marca com ilustração do banho e tosa, e a **marca perdeu as três bolinhas**); **(2)** a **semana em lista** (virou **grade de horários**, seção 5); **(3)** o **"fio do dia"** como linguagem da Agenda (o Dia virou **linhas limpas com avatar do pet**, a Fila ficou em linhas com a posição em número grande). Onde as seções 1, 2, 3 e 5 abaixo falam em fio, pontos de estado como assinatura da marca ou semana em lista, **vale esta revisão** e o estudo `docs/estudo/redesign-r6-agenda-grade.md` (as seções antigas ficam como histórico da decisão, marcadas). Os pontos de estado (anel, meio, cheio) continuam existindo **só onde há um estado a mostrar**, sempre com o texto ao lado. A semana é uma grade a partir de 880 px de **área de conteúdo**; a altura da hora cresce quando há atendimento curto (o menor bloco tem pelo menos 34 px) e, como cada dia tem um botão de 44 px que abre o mesmo dia, o bloco curto da grade cumpre a exceção de **controle equivalente** do WCAG 2.5.8.

## O que muda e o que NÃO muda
O redesign é **apresentação e experiência**, não uma chance de reescrever o domínio. **Não muda, nem sem discussão e aprovação separada:** regras de disponibilidade, agendamento e proteção contra reserva dupla; máquina de estados dos agendamentos; cancelamento e remarcação; autorização por perfil e vínculo com o prestador; contratos da API e integridade do banco; fuso horário e antecedência; escopo funcional do MVP. **Fora de escopo:** pagamentos, preços, fotos, notificações, novas integrações, aplicativo móvel nativo. As únicas telas "novas" usam APIs que já existem: **página pública do estúdio** (ADR 0015) e **semana da agenda** (`GET /api/agenda/week`).

## 1. Identidade (a ideia do "fio do dia" foi substituída na v2.1: ver a revisão acima)
- **Marca visível:** **Pelo & Ponto**. O `&` nunca entra em endereço (`pelo-e-ponto`) nem em nome de arquivo; o nome técnico do repositório continua QueueCare. **Slogan:** "Agenda certa. Pet bem cuidado."
- **Ideia central:** o nome vem de *ponto*, de costura. **Um fio pespontado liga os atendimentos do dia**: **pontilhado** enquanto o atendimento falta, **cheio** quando ele termina. É a única ousadia visual; o resto é calmo. O fio aparece **onde representa o domínio** (a sequência de horários da Agenda e da Semana) e **cede espaço** quando a tela tem outra lógica (a **Fila** é ordem de chegada: números de posição, sem fio).
- **Posicionamento:** agenda e fila para estúdios de banho e tosa, feitas para quem está de pé atendendo (a hora e o nome do pet se leem de longe, alvos grandes, texto legível) e para o tutor que só quer marcar o horário do seu pet. Tom: acolhedor e direto, sem gíria nem humor forçado; frases curtas em português do Brasil.
- **Logotipo:** só tipografia e forma, sem fotos nem mascote: a palavra em Bricolage Grotesque com o `&` em dourado. **Os três pontos que acompanhavam a marca na v2 inicial foram retirados** (o dono perguntou o que significavam, e fora da Agenda não significam nada); os pontos de estado (anel = agendado, meio preenchido = chegou, cheio = concluído) existem **só onde há um estado a mostrar**, sempre com o texto do estado ao lado. Rejeitados pelo dono e fora do escopo: APARO/FELPA e a "ficha de bancada".
- **Sem fotos:** a personalidade vem de tipografia, cor e forma; custo zero (nenhum arquivo de imagem).

## 2. Direção visual
**Paleta** (azul de banho, louça azulada, tinta e o dourado de plaquinha de coleira; **todos os pares abaixo foram medidos** pela fórmula WCAG 2.2; texto precisa de 4,5:1, componente de interface e foco de 3:1). O dourado é **preenchimento e marca, nunca texto nem contorno sobre claro** (sobre branco é só 1,9:1).

| Token (claro) | Valor | Uso e contraste medido |
|---|---|---|
| `--bg` louça | `#EEF2F4` | fundo da página |
| `--surface` | `#FFFFFF` | campos e opções |
| `--text` tinta | `#14202B` | 14,7:1 no fundo, 16,5:1 no branco |
| `--muted` | `#46555F` | apoio: 6,8:1 no fundo, 7,7:1 no branco |
| `--primary` azul de banho | `#115E7A` | ação, link, foco e fio do agendado: branco sobre ele 7,2:1; ele sobre o fundo 6,4:1 |
| `--primary-strong` | `#0B4A61` | hover e pressionado: branco sobre ele 9,7:1 |
| `--gold` ouro de coleira | `#F2B138` | **só preenchimento** (botão de destaque, etiqueta "hoje" e "agora"); tinta sobre ele 8,7:1 |
| `--gold-mark` | `#B97F00` | marcas e o `&` do logotipo (≥ 3:1, texto grande); sobre o fundo 3,1:1 |
| `--gold-text` | `#7A5200` | quando o dourado precisa ser texto: 6,9:1 no branco |
| `--field` cinza azulado | `#6B7B86` | borda de campo e fio pontilhado: 4,4:1 no branco, 3,9:1 no fundo |
| `--line` | `#CBD5DB` | divisórias que não carregam informação |
| `--danger` | `#A3231A` | erro e ação destrutiva: 7,5:1 no branco |

**Estados do atendimento** (sempre **forma + texto + cor**; a cor nunca é a única pista; o estado visual vem do **status real** do atendimento, nunca de horário ou de cor): **agendado** = anel `#115E7A`, rótulo `#0B4A61` sobre `#DCEBF1` (7,9:1); **chegou** = ponto **meio preenchido** em `#B97F00`, rótulo `#5E3F00` sobre `#FCE9BE` (8,0:1); **concluído** = ponto **cheio com marca de verificação** e fio cheio em `#1D5A46`, rótulo `#1D5A46` sobre `#D8EEE4` (6,6:1); **cancelado** = ponto com **traço**, hora riscada, rótulo `#46555F` sobre `#E4E8EB` (6,3:1). Cada marcador tem **texto acessível** (o rótulo do estado). Os fios (3 a 4 px) têm 6,4:1 (agendado) e 7,2:1 (concluído) sobre o fundo.

**Tema escuro** ("noite de estúdio", um tema de verdade, não uma inversão): `--bg #0E1721`, `--surface #16212D`, `--text #E8EEF3` (15,4:1 e 13,9:1), `--muted #A3B2BE` (8,3:1 e 7,5:1), `--primary #7CC4E0` com texto `#07202B` por cima (8,7:1; ele sobre o fundo 9,3:1), `--gold #F5BE52` (sobre o fundo 10,6:1; tinta sobre ele 9,7:1), `--field #6F808D` (4,0:1 e 4,4:1), perigo `#FF9C92` (8,1:1), foco `#9ADCF2`. Estados no escuro: agendado `#A9DBEE/#173443` (8,7:1), chegou `#F8D58A/#40300C` (9,0:1), concluído `#A5E0C6/#143A2D` (8,4:1), cancelado `#B5C2CC/#26323D` (7,2:1); fio concluído `#A5E0C6` sobre o fundo 12,1:1. Além do texto, valida-se no navegador **foco, hover, desabilitado, erro e seleção** nos dois temas.

**Tipografia (duas famílias, bem diferentes):** **Bricolage Grotesque** (títulos, **horas**, nomes dos pets, números, em peso 600 a 700, `tabular-nums`) e **Atkinson Hyperlegible Next** (texto e interface, desenhada para legibilidade, boa para quem atende em pé). As duas **hospedadas junto do site** por `@fontsource-variable` (nenhum pedido a terceiros; custo zero; só o subconjunto latino, eixo de peso): **41,4 + 34,0 = 75,4 kB** gzip, dentro do orçamento. **Algarismos:** o zero de Atkinson vem cortado por uma barra, estranho em horário no texto; por isso os dígitos de toda a interface vêm **sempre da Bricolage**, por uma regra `@font-face` com `unicode-range: U+0030-0039` (a mesma família, só os algarismos) na frente da pilha de texto; `font-display: swap` com métricas de reserva (`size-adjust`) para não haver salto de layout. Escala (rem): 0,8125 · 0,875 · 1 · 1,125 · 1,375 · 1,75 · 2,25 · 2,75; corpo 16 px, entrelinha 1,5; linhas de leitura abaixo de 70 caracteres; títulos 1,05 a 1,2.
**Espaço e forma:** grade de 4 px (`--space-1` a `--space-12`), raios **6 / 10 / 999 px** (botões e campos 10; etiquetas 6; rótulos de estado e pontos redondos); **sem cartões repetidos**: estrutura por fio, espaço e tipografia; contorno de **2 px** nos campos e botões secundários; **uma** sombra leve só para o que flutua (a gaveta). Alvos de toque de **44 px** em largura e altura.
**Ícones:** poucos e funcionais (menu, fechar, setas de dia, mais, lixeira), com **Lucide** (`lucide-react`, importados um a um), decorativos com `aria-hidden`, botão só de ícone sempre com nome acessível. **Os estados não usam ícone de biblioteca:** são formas próprias de CSS (anel, meio, cheio, traço).
**O fio, em CSS puro** (sem SVG nem lógica de posição): uma borda lateral `dotted` (pontilhada, 3 px) no item e uma sólida (4 px) quando o atendimento está concluído; o ponto é um círculo de 24 px (20 px na semana) com a forma do estado; "agora" é uma etiqueta dourada sobre uma linha dourada entre o último atendimento passado e o próximo.
**Movimento:** o mínimo: foco, abertura da gaveta e carregamento (≤ 150 ms); nada animado ao carregar a página; `prefers-reduced-motion` desliga tudo.

## 3. Design system (componentes)
Todos em `frontend/src/ui/`, com testes de comportamento e verificação visual no PR que os cria. Substituem as classes soltas do CSS atual, migradas tela a tela.
- **Tokens** (cor, tipo, espaço, raio, sombra, movimento) em variáveis CSS **semânticas** (`--surface`, `--text-muted`, `--st-wait-bg`...), com os dois temas.
- **Button:** primário (azul), **destaque** (dourado, a ação do momento: "Começar atendimento"), secundário (contorno), silencioso e perigo; estado de carregando ("Salvando…") que bloqueia o envio duplo; **44 px de altura e de largura mínimas**, inclusive os silenciosos ("Remarcar", "Cancelar"); foco em anel de 3 px.
- **Field:** rótulo sempre visível, dica, **erro ligado ao campo** (`aria-describedby` e `aria-invalid`, **anunciado a leitores de tela**, com ícone de exclamação e texto, nunca só a cor), em campo de texto, seleção, área de texto; **opções** (rádio e caixa) em linhas inteiras clicáveis de 56 px; **horários livres** como grade de botões, os indisponíveis tracejados e riscados e desabilitados. O erro nunca repete o valor digitado.
- **Fio e Linha de Atendimento:** o componente de assinatura: **hora grande** (Bricolage, 30 px; 22 px no celular), ponto do estado, **nome do pet em destaque**, tutor, rótulo do estado, serviço, porte e duração; ações da linha (marcar chegada, começar atendimento, concluir, remarcar, cancelar) abaixo do texto; histórico expansível. Usado na Agenda (dia), na Semana (versão compacta) e nos Meus agendamentos.
- **Barra lateral e gaveta:** navegação só de texto; o item ativo ganha um trecho de fio cheio e os demais um trecho pontilhado; rodapé com o usuário, o estúdio e o tema.
- **Controle segmentado** (Dia / Semana) e **Empty state** (frase e ação) para toda lista vazia; **Notice** (informação, sucesso, aviso, erro) com `role` certo; **resumo do dia em frase** (em vez de blocos de números).
- **Confirmação:** continua **na própria linha** (já testada, foco previsível: abre em "Voltar"); **não haverá modal** no MVP. A única janela de sobreposição é a **gaveta do celular**, com `<dialog>` nativo.
- **Carregando e erro:** o componente de carregando atual é mantido e os estados de erro e de dados velhos (aviso com "Tentar de novo") continuam como já testados.

## 4. Layout e navegação
- **Computador (≥ 901 px de janela):** **barra lateral fixa de 232 px** (só texto) (marca com os três pontos no topo, navegação por perfil, e no rodapé o usuário, o estúdio, o tema e "Sair"); conteúdo de até 1280 px no total, com a linha do tempo da Agenda limitada a ~760 px de leitura, títulos de página com ação principal à direita.
- **Tablet e celular (≤ 900 px de janela):** barra superior com a marca e o botão de menu; o menu abre como gaveta (`<dialog>`) e o ciclo de foco é critério verificado: **foco inicial** dentro da gaveta; **foco contido** nela e **fundo inerte** (não clicável nem focável) enquanto aberta; **Esc** fecha; **fechar ao navegar** sem deixar o foco num elemento removido (vai para o título da nova página); **retorno do foco** ao botão que abriu; e o foco **nunca fica encoberto** por barras fixas. Sem rolagem horizontal a partir de **320 px**. As ações de cada linha cabem sem esconder o texto.
- **Navegação por perfil:** PROVIDER (Início, **Agenda**, **Fila**, Serviços, Expediente, Equipe, Meu negócio); ATTENDANT (Início, **Agenda**, **Fila**); CUSTOMER (Início, Agendar, Meus agendamentos, Meus pets); ADMIN (Contas, Novo prestador). **Agenda e Fila são telas separadas**; a **semana é uma modalidade da Agenda** (controle Dia/Semana na mesma tela).
- **Rotas atuais não mudam** (`/agenda`, `/fila`, `/agendar`...); só entram `/p/:endereco` (página pública) e o modo semana da agenda (parâmetro de endereço).
- **Atalho para o conteúdo** no topo (skip link), marcos (`header`, `nav`, `main`), título de página único por tela e foco movido ao título quando a rota muda.

## 5. Páginas (escopo e estrutura)
| Tela | Estrutura |
|---|---|
| Entrar, Cadastrar | **painel dividido** no computador: à esquerda a marca, a promessa ("Banho e tosa na hora certa.") e uma **ilustração do universo do banho e tosa** (cachorro dourado no banho, com chuveiro, espuma e banheira, em formas vetoriais, decorativa, escondida de leitores de tela; o painel é azul-água no tema claro e azul-petróleo profundo no escuro); à direita o formulário em um cartão; no celular, a marca em uma faixa curta acima do formulário; botão "Mostrar senha" nos campos de senha; a mesma composição vale para a página não encontrada de quem não entrou |
| Trocar senha | dentro da casca (barra lateral), com os campos do design system; erros por campo |
| Início por perfil | **Equipe:** "Hoje" com o resumo (agendados, na fila, concluídos, próximo atendimento) e atalhos; **Cliente:** próximo agendamento e atalhos; **Admin:** atalhos |
| **Agenda** (dia e semana) | **(v2.1)** cabeçalho com título, controle Dia/Semana, navegação e data em uma faixa; resumo do dia em frase; **Dia:** linhas limpas em uma folha, com hora, **avatar do pet**, nome do pet em destaque (raça e porte em texto de apoio), tutor, serviço, estado em pílula e ações, e o marcador "agora"; **Semana:** **grade de horários** a partir de **880 px de área de conteúdo** (blocos pela hora e duração, linha de "agora", dia e hora do fuso do negócio) e, abaixo disso, **uma seção por dia** com os mesmos dados e a etiqueta "agora" ao lado de "hoje" |
| **Fila** | **sem o fio** (a Fila é ordem de chegada, não sequência de horários): quem **já chegou**, com **número de posição**, **horário marcado**, **tempo de espera** ("espera de 12 min", recalculado a cada minuto) e "Concluir atendimento". **Delimitação de domínio (R5):** a separação "Em atendimento" x "Esperando" e a ação "Começar atendimento" **dependem de um estado novo** (hoje o atendimento vai de "chegou" direto a "concluído"); isso é **mudança de domínio** e fica **fora do redesign**, para um ADR próprio. A prévia do ADR mostrava essa separação como ideia visual |
| Serviços, Expediente, Equipe, Meu negócio | formulários e listas com o design system; Meu negócio inclui o endereço público e "publicar" |
| **Página pública do estúdio** (`/p/:endereco`) | **sem fotos** (tipografia e formas): nome, fuso, serviços (nome e duração), chamada "Agendar um horário" que leva a entrar ou cadastrar e depois ao agendamento já no estúdio; estado "não encontrado" igual para inexistente e não publicado (ADR 0015) |
| Agendar | passos (prestador, serviço, dia, horário, pet, confirmação) com indicador de progresso; confirmação colada ao pé no celular |
| Meus agendamentos, Meus pets | listas com a Linha de Atendimento e ações; remarcar e histórico como hoje |
| Admin (contas, novo prestador) | tabelas/listas com busca, ativar, desativar, redefinir senha |
| 404 e estados vazios | frase, ícone e próxima ação |

## 6. Tema claro e escuro
Tokens semânticos nos dois temas (tabelas acima). O tema **segue o sistema** por padrão e o usuário pode **forçar claro ou escuro** no rodapé do menu; a escolha fica em `localStorage` (com `try/catch`: se falhar, segue o sistema). **Sem flash:** um pequeno script em linha no `index.html` aplica o tema **antes da primeira renderização visível**, para que ninguém com preferência escura veja a tela clara por um instante; o critério é verificado no navegador (captura do primeiro quadro com a preferência escura salva). Nenhum componente tem cor fixa.

## 7. Acessibilidade (WCAG 2.2 AA)
Contraste medido (tabelas acima); foco **sempre visível** (anel de 3 px, nunca encoberto por barras fixas, WCAG 2.4.11); teclado em tudo (ordem lógica, sem armadilha); gaveta com o ciclo de foco completo da seção 4; alvos de 44 px; `lang="pt-BR"`; rótulos, dicas e erros ligados aos campos; avisos em regiões `status` e `alert`; título da página por rota; nada depende só de cor; reflow sem rolagem horizontal a 320 px; texto cresce até 200 % sem perder conteúdo; `prefers-reduced-motion` respeitado.

## 8. Como se verifica "perfeito" (critérios mensuráveis)
Um site "perfeito" não é verificável; estes critérios são. **Cada PR de tela os cumpre e mostra a evidência:**
1. **Os testes existentes passam** (backend e front) e a mutação dos comportamentos tocados continua.
2. **Navegador real (Playwright):** a tela é aberta, percorrida e fotografada em **1280 px e 390 px**, nos temas **claro e escuro** (4 capturas por tela, anexadas no PR e mostradas ao Matheus, que aprova o visual; a verificação do primeiro quadro com tema escuro salvo entra na fundação).
3. **axe-core** (`@axe-core/playwright`): **zero violações graves ou críticas** por tela e tema; as moderadas ficam justificadas.
4. **Teclado:** percurso por Tab em ordem lógica e **foco visível em todo elemento interativo, medido pelos pixels e não pelo CSS** (o script fotografa o elemento com e sem foco e compara os pixels: marca o que mudou com **contraste de pelo menos 3:1** e exige, **em cada um dos quatro lados**, uma faixa de pelo menos **2 px** de espessura em 95 % do trecho reto do lado (anel por fora, no limite ou por dentro; os cantos arredondados ficam de fora do trecho reto) e que o **interior do elemento não tenha mudado em sua maior parte**, porque fundo que muda inteiro não é anel; o desenho do produto é de 3 px, o piso medido é o da WCAG 2.4.13; sombra transparente, contorno sem contraste, contorno de 1 px, anel em um lado só e mudança só de fundo reprovam, e o próprio verificador tem teste com esses casos), e Esc fecha o menu.
5. **Contraste:** pares críticos conferidos com a fórmula (a mesma tabela deste ADR) e, no navegador, pelo axe.
6. **Responsivo e zoom:** sem rolagem horizontal em **320, 390, 768, 1024 e 1280 px**; alvos ≥ 44 px **de largura e de altura** (em caixas de marcar e botões de opção vale a área do controle somada à do rótulo); **zoom de 200 %** sem perda de conteúdo nem de função, especialmente formulários, menu, Agenda e Fila; a **semana** é validada na largura intermediária (1024 px), além do computador e do celular; o **ciclo de foco da gaveta** (seção 4) tem teste no menu do celular.
7. **Desempenho, com orçamento FIXADO no R1.** **Baseline medido** (build de produção, resposta a resposta com `gzip -9`, que é mais pesado que o Brotli da hospedagem e por isso conservador; `npm run e2e:peso`): **primeira visita a `/entrar`: 183,4 kB** = JS 103,2 + CSS 6,8 + fontes 73,1 + 0,3; hoje há **um único pacote de JS** (nada é carregado a mais depois de entrar nem ao abrir a agenda). **Fontes escolhidas, medidas:** Bricolage Grotesque (latim, eixo de peso) 41,4 kB + Atkinson Hyperlegible Next (latim, eixo de peso) 34,0 kB = **75,4 kB**; ~10 ícones Lucide ≈ 4 kB. **Metas fixadas** (por recurso, medidas por rota com o mesmo comando, sempre separando o que a primeira visita carrega do que chega a mais ao abrir outra tela): **JS inicial ≤ 130 kB**, **CSS ≤ 14 kB**, **fontes ≤ 80 kB** (os dois arquivos latinos de eixo de peso, 75,4 kB; latim estendido só se a página usar; a regra de algarismos reaproveita o arquivo da Bricolage, sem arquivo a mais), **primeira visita a `/entrar` ≤ 230 kB no total**, **nenhum pedido a terceiros**, sem salto de layout visível ao carregar as fontes (`font-display: swap` com métricas de reserva). Código das rotas menos usadas (admin, página pública) é carregado sob demanda e medido à parte. Se algum PR estourar uma meta, o PR justifica e este ADR é atualizado, não ignorado.
8. **Sem regressão funcional** nos fluxos tocados (testes de front e a verificação em navegador).
A **suíte E2E crítica consolidada** vem **depois** da estabilização: agendamento completo (inclusive a partir da página pública), cancelamento e remarcação pelo cliente, check-in e fila pela equipe, e controle de acesso entre cliente e equipe (3 a 5 fluxos, sem automatizar cada detalhe visual).

## 9. Ambiente de desenvolvimento e verificação
Docker nem sempre está ligado, então o ambiente local usa um **servidor de desenvolvimento com PostgreSQL embutido** (o mesmo dos testes), com **dados fictícios de demonstração** (estúdio, equipe, clientes, pets, agendamentos em todos os estados) criados por um roteiro de carga; sobe a API e o Vite juntos. O Playwright usa esse ambiente. Nada disso vai para a produção, e as senhas são de teste.

## Plano (PRs pequenos, em ordem de dependência; cada um revisado pelo ChatGPT, com mutação onde houver comportamento)
| PR | Escopo | Esforço |
|---|---|---|
| R1 | Ambiente local com dados de demonstração + Playwright e axe + roteiro de capturas e de checagens + **medição do baseline e fixação dos orçamentos de desempenho** | ALTO |
| R2 | Fundação: tokens, fontes (com a regra de algarismos), tema claro e escuro (com alternância e sem flash), componentes base (Button, Field, Estado e ponto, Fio, Notice, Resumo). **Condição:** testados numa **página de demonstração isolada** (rota só de desenvolvimento, fora do build de produção), **sem migrar as telas atuais nem redesenhar o layout**: a substituição da estrutura visual é do R3 em diante | ALTO |
| R3 | Estrutura: barra lateral, gaveta no celular (com o ciclo de foco testado), menu por perfil, atalho para o conteúdo, títulos e foco por rota | ALTO |
| R4 | Entrada: entrar, cadastrar, trocar senha, 404, Início por perfil | MÉDIO |
| R5 | Agenda do dia (o fio e a Linha de Atendimento) e Fila (sem o fio) | ALTO |
| R6 | Agenda da semana (um fio por coluna; sete colunas a partir de 880 px de área, lista por dia abaixo) | MÉDIO |
| R7 | Cliente: Agendar, Meus agendamentos, Meus pets | MÉDIO |
| R8 | Prestador: Serviços, Expediente, Equipe, Meu negócio | MÉDIO |
| R9 | Página pública do estúdio e o endereço público no Meu negócio; agendar já no estúdio | MÉDIO |
| R10 | Admin | MÉDIO |
| R11 | Polimento: estados vazios e de erro, microtexto, auditoria final de acessibilidade e desempenho | MÉDIO |
| R12 | Suíte E2E crítica consolidada | ALTO |

## Riscos a atacar na revisão
Redesign tocando comportamento por engano (os testes de front são o freio); o peso de fontes e pacotes; o foco e a gaveta no celular; a semana em 7 colunas em telas médias; a página pública e o redirecionamento ao agendar já no estúdio; ícones sem nome acessível; contraste real no navegador diferente do medido; a "perfeição" como critério subjetivo (por isso os critérios mensuráveis e a aprovação visual do dono por capturas).

## Atualização de 21/11/2026: medição de peso depois dos ADRs 0019 a 0023 e carregamento sob demanda
Medido com `npm run e2e:peso` (build de produção, gzip -9 de cada resposta, mesma metodologia do baseline):

| Momento | JS | CSS | Fontes | **Primeira visita a `/entrar`** |
|---|---|---|---|---|
| Baseline do R1 (17/11) | 103,2 kB | 6,8 kB | 73,1 kB | 183,4 kB |
| Depois dos ADRs 0019 a 0023 (pacote único) | 122,3 kB | 11,1 kB | 75,4 kB | **209,3 kB** |
| **Com as telas sob demanda (este PR)** | **99,8 kB** | 11,1 kB | 75,4 kB | **186,8 kB** |

- Todas as metas do R1 continuam cumpridas (JS ≤ 130, CSS ≤ 14, fontes ≤ 80, primeira visita ≤ 230 kB). O JS estava a 8 kB do limite com o pacote único: o produto cresceu (duração por pet, política, faltas, lembrete, lista de espera, sinal por Pix, página pública) sem folga para a próxima fatia.
- **Decisão:** as telas da equipe, do administrador e do agendamento (agenda, fila, serviços, expediente, equipe, meu negócio, contas, novo prestador, agendar, meus agendamentos, pets, perfil) viram **pacotes sob demanda** (`React.lazy` por rota, com o espaço da tela mantido pelo `Loading`). Entrar, cadastro, Início, página pública e troca de senha ficam no pacote inicial. É o que o item 7 já dizia ("código das rotas menos usadas é carregado sob demanda") e que o pacote único não cumpria.
- **Por quê importa:** o visitante da página pública (link compartilhado, quase sempre no celular e em rede móvel) baixa só o essencial, sem o código da agenda e do administrador. Cada tela chega depois com 1 a 6 kB (a maior, a Agenda, 6,1 kB gzip).
- Limitação do `e2e:peso`: as linhas "depois de entrar" e "/agenda" mostram 0,0 kB a mais (a medição não contabiliza os pacotes preguiçosos carregados por navegação interna); os tamanhos por tela vêm da saída do `vite build`.
