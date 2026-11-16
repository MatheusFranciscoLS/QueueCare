# ADR 0018: redesign completo (Pelo & Ponto)

- **Status:** Accepted (ChatGPT: APPROVE no commit 48afaa5, após um REQUEST CHANGES; o Matheus escolheu esta ordem e autorizou seguir para a implementação). Observação do revisor para o R1: registrar também o tamanho real transferido por rota, sem confundir o tamanho total do build com o inicial carregado.
- **Data:** 16/11/2026
- **Esforço:** ALTO (muda toda a apresentação, a navegação e a forma de verificar o produto)
- **Contexto:** o produto funciona e tem a lógica bem protegida (581 testes de backend e 325 de front, com mutação), mas o visual nasceu como "sistema interno": um cabeçalho com menu em linha, cartões iguais, identidade genérica (ADR 0014, verde-petróleo, Inter e Manrope) e **nenhuma tela jamais foi verificada em navegador real** (só jsdom). O dono quer um produto **profissional, moderno e atual** para o nicho aprovado (banho e tosa), no nível do portfólio, sem cara de gerado por IA.
- **Ordem decidida** (Matheus e ChatGPT concordam): **este ADR, depois o redesign em PRs pequenos, cada um com verificação em navegador real, e só depois uma suíte E2E enxuta.** Escrever uma suíte E2E extensa agora seria retrabalho (as telas e seletores vão mudar); deixar toda a verificação de navegador para o final seria arriscado (foco, celular e contraste só aparecem em navegador real).

## O que muda e o que NÃO muda
O redesign é **apresentação e experiência**, não uma chance de reescrever o domínio. **Não muda, nem sem discussão e aprovação separada:** regras de disponibilidade, agendamento e proteção contra reserva dupla; máquina de estados dos agendamentos; cancelamento e remarcação; autorização por perfil e vínculo com o prestador; contratos da API e integridade do banco; fuso horário e antecedência; escopo funcional do MVP. **Fora de escopo:** pagamentos, preços, fotos, notificações, novas integrações, aplicativo móvel nativo. As únicas telas "novas" usam APIs que já existem: **página pública do estúdio** (ADR 0015) e **semana da agenda** (`GET /api/agenda/week`).

## 1. Identidade
- **Marca visível:** **Pelo & Ponto**. O `&` nunca entra em endereço (`pelo-e-ponto`) nem em nome de arquivo; o nome técnico do repositório continua QueueCare. **Slogan:** "Agenda certa. Pet bem cuidado."
- **Posicionamento:** agenda e fila para estúdios de banho e tosa, feitas para quem está de pé atendendo (rápidas de ler, com alvos grandes) e para o tutor que só quer marcar o horário do seu pet. Tom: acolhedor e direto, sem gíria nem humor forçado; frases curtas em português do Brasil.
- **Logotipo:** só tipografia e forma (sem fotos, sem mascote): a palavra em Archivo, com o `&` em coral, e a **Linha de Atendimento** (três pontos) como assinatura, que já existe e o dono aprovou manter. Rejeitados pelo dono e fora do escopo: APARO/FELPA e a "ficha de bancada".
- **Sem fotos:** a personalidade vem de tipografia, cor e formas; isso também é custo zero (nenhum arquivo de imagem para hospedar).

## 2. Direção visual
**Paleta aprovada** (cada par abaixo foi **medido** pela fórmula WCAG 2.2; texto precisa de 4,5:1, componente de interface e foco de 3:1):

| Token (claro) | Valor | Uso e contraste medido |
|---|---|---|
| `--bg` névoa | `#F5F6F3` | fundo da página |
| `--surface` | `#FFFFFF` | cartões, campos |
| `--text` grafite vegetal | `#202824` | 13,9:1 no fundo, 15,1:1 no cartão |
| `--muted` | `#4F5B54` | texto de apoio: 6,6:1 no fundo, 7,1:1 no cartão |
| `--primary` verde bosque | `#275B4B` | ação, link e foco: texto branco sobre ele 7,8:1; ele sobre o fundo 7,2:1 |
| `--primary-strong` | `#1C4639` | pressionado e hover: branco sobre ele 10,6:1 |
| `--accent` coral | `#F27A68` | **destaque, nunca texto**: sobre branco é só 2,7:1; com grafite por cima 5,6:1 |
| `--accent-text` coral escuro | `#A8402F` | quando o coral precisa ser texto: 5,6:1 |
| `--lime` lima musgo | `#A6B76A` | preenchimento do "concluído"; grafite sobre ele 6,9:1 |
| `--field-line` cinza folha | `#77847C` | borda de campo: 3,9:1 sobre branco e 3,6:1 sobre a névoa (≥ 3:1) |
| `--line` | `#D9DDD5` | divisórias decorativas (não carregam informação) |
| `--danger` | `#A3231A` | erro e ação destrutiva: 7,5:1 |

**Estados do atendimento** (o estado é sempre **escrito** e tem **ícone**; a cor nunca é a única pista): agendado `#275B4B` sobre `#E3EEE9` (6,6:1); chegou/esperando `#8A2F1F` sobre `#FDE6E1` (7,0:1); concluído `#3A4710` sobre `#E6EDCB` (8,3:1); cancelado `#4F5B54` sobre `#E6E8E2` (5,8:1).

**Tema escuro** (valores definidos agora e medidos; o escuro é um tema de verdade, não uma inversão): `--bg #121815`, `--surface #1A2420`, `--text #E8EDE9` (15,2:1 e 13,5:1), `--muted #A7B4AC` (8,4:1 e 7,4:1), `--primary #7CC4A6` com texto `#0E1F18` por cima (8,4:1; ele sobre o fundo 8,8:1), foco `#97D5BB` (10,8:1), `--field-line #6F8077` (3,8:1), coral como texto `#F58F7F` (7,8:1), perigo `#FF9C92` (7,9:1). Estados no escuro: agendado `#9ADBC0/#1F3A2F` (7,8:1), esperando `#FFB4A6/#4A2119` (8,1:1), concluído `#D3E39B/#2E3A12` (8,8:1), cancelado `#B8C3BC/#2A332E` (7,2:1).

**Tipografia:** **Archivo** (títulos, números e horários, com `tabular-nums`) e **DM Sans** (texto e interface), ambas **hospedadas junto do site** pelos pacotes `@fontsource-variable` (nenhum pedido a terceiros; custo zero; só o subconjunto latino é baixado). Escala (rem): 0,8125 · 0,875 · 1 · 1,125 · 1,375 · 1,75 · 2,25; corpo 16 px com entrelinha 1,5; títulos 1,15 a 1,25; pesos 400, 500, 600 (e 700 só nos títulos). Horário na agenda é a informação principal: Archivo 600.
**Espaço e forma:** grade de 4 px (`--space-1` a `--space-12`), raios 6 / 10 / 16 px, **bordas em vez de sombras** (uma sombra só, bem leve, para elementos que flutuam), alvos de toque de 44 px.
**Ícones:** **Lucide** (pacote `lucide-react`, árvore de dependências leve e sem custo), decorativos com `aria-hidden`; botão só de ícone sempre tem nome acessível. Ícone nunca substitui o texto em ação importante.
**Movimento:** curto (≤ 150 ms), só para foco, abertura de menu e carregamento; `prefers-reduced-motion` desliga tudo.

## 3. Design system (componentes)
Todos em `frontend/src/ui/`, com testes de comportamento e verificação visual no PR que os cria. Substituem as classes soltas do CSS atual, migradas tela a tela.
- **Tokens** (cor, tipo, espaço, raio, sombra, movimento) em variáveis CSS **semânticas** (`--surface`, `--text-muted`, `--status-waiting-bg`...), com os dois temas.
- **Button:** primário, secundário, fantasma e perigo; tamanhos; estado de carregando ("Salvando…") que bloqueia o envio duplo; alvo de 44 px; foco de 3 px.
- **Field:** rótulo sempre visível, dica, erro ligado ao campo (`aria-describedby`, `aria-invalid`), em campo de texto, seleção, área de texto, caixa e opção. O erro nunca repete o valor digitado.
- **Card e Section:** cabeçalho, corpo e ações; sem cartão dentro de cartão.
- **Badge de estado:** texto + ícone + cor, os quatro estados; **Notice** (informação, sucesso, aviso, erro) com `role` certo (`status` ou `alert`).
- **Lista de atendimentos (Linha de Atendimento):** o componente de assinatura: linha do tempo com horário em destaque, nome do tutor, pet (nome, espécie, porte), serviço e estado; ações da linha (chegou, remarcar, cancelar, concluir) e histórico expansível; é usado na Agenda e na Fila.
- **Tabs / controle segmentado** (Dia/Semana) e **Empty state** (ícone, frase e ação) para toda lista vazia.
- **Confirmação:** continua **na própria linha** (já testada, foco previsível: abre em "Voltar"); **não haverá modal** no MVP. A única janela de sobreposição é o **menu lateral no celular**, feita com `<dialog>` nativo (foco preso e Esc de graça).
- **Carregando e erro:** o componente de carregando atual é mantido e os estados de erro e de dados velhos (aviso com "Tentar de novo") continuam como já testados.

## 4. Layout e navegação
- **Computador (≥ 1024 px):** **barra lateral fixa de 240 px** (marca no topo, grupos de navegação por perfil, e no rodapé o usuário, o perfil, o tema e "Sair"); conteúdo com largura máxima de leitura (~72 rem), títulos de página com ação principal à direita.
- **Tablet e celular (< 1024 px):** barra superior com a marca e o botão de menu; o menu abre como gaveta (`<dialog>`) e o ciclo de foco é critério verificado: **foco inicial** dentro da gaveta; **foco contido** nela e **fundo inerte** (não clicável nem focável) enquanto aberta; **Esc** fecha; **fechar ao navegar** sem deixar o foco num elemento removido (vai para o título da nova página); **retorno do foco** ao botão que abriu; e o foco **nunca fica encoberto** por barras fixas. Sem rolagem horizontal a partir de **320 px**. As ações de cada linha cabem sem esconder o texto.
- **Navegação por perfil:** PROVIDER (Início, **Agenda**, **Fila**, Serviços, Expediente, Equipe, Meu negócio); ATTENDANT (Início, **Agenda**, **Fila**); CUSTOMER (Início, Agendar, Meus agendamentos, Meus pets); ADMIN (Contas, Novo prestador). **Agenda e Fila são telas separadas**; a **semana é uma modalidade da Agenda** (controle Dia/Semana na mesma tela).
- **Rotas atuais não mudam** (`/agenda`, `/fila`, `/agendar`...); só entram `/p/:endereco` (página pública) e o modo semana da agenda (parâmetro de endereço).
- **Atalho para o conteúdo** no topo (skip link), marcos (`header`, `nav`, `main`), título de página único por tela e foco movido ao título quando a rota muda.

## 5. Páginas (escopo e estrutura)
| Tela | Estrutura |
|---|---|
| Entrar, Cadastrar, Trocar senha | cartão estreito centrado com a marca e a frase de apresentação; campos do design system; erros por campo |
| Início por perfil | **Equipe:** "Hoje" com o resumo (agendados, na fila, concluídos, próximo atendimento) e atalhos; **Cliente:** próximo agendamento e atalhos; **Admin:** atalhos |
| **Agenda** (dia e semana) | cabeçalho com data, navegação por dia e controle Dia/Semana; **dia:** Linha de Atendimento do dia; **semana:** comportamento explícito pela LARGURA DA ÁREA DE CONTEÚDO (não pela da janela): **≥ 960 px, sete colunas** (janela de ~1200 px ou mais, já descontada a barra lateral de 240 px); **abaixo disso, lista por dia** empilhada (uma seção por dia, com o estado de cada atendimento). Legibilidade vale mais que manter sete colunas: a semana é validada em 1024 px, que cai no segundo caso |
| **Fila** | quem chegou, na ordem de chegada, com posição, tempo de espera e "Concluir atendimento" |
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
7. **Desempenho, com orçamento FIXADO no R1.** **Baseline medido** (build de produção, resposta a resposta com `gzip -9`, que é mais pesado que o Brotli da hospedagem e por isso conservador; `npm run e2e:peso`): **primeira visita a `/entrar`: 183,4 kB** = JS 103,2 + CSS 6,8 + fontes 73,1 + 0,3; hoje há **um único pacote de JS** (nada é carregado a mais depois de entrar nem ao abrir a agenda). **Candidatos medidos:** Archivo (latim, eixo de peso) 34,9 kB + DM Sans (latim, eixo de peso) 36,9 kB = **71,8 kB** de fontes (menos que as 73,1 de hoje); 20 ícones Lucide ≈ 8 kB em módulos soltos mais ~4 kB de miolo comum, bem menos depois do empacotamento. **Metas fixadas** (por recurso, medidas por rota com o mesmo comando, sempre separando o que a primeira visita carrega do que chega a mais ao abrir outra tela): **JS inicial ≤ 130 kB**, **CSS ≤ 14 kB**, **fontes ≤ 80 kB** (os dois arquivos latinos de eixo de peso; latim estendido só se a página usar), **primeira visita a `/entrar` ≤ 230 kB no total**, **nenhum pedido a terceiros**, sem salto de layout visível ao carregar as fontes (`font-display: swap` com métricas de reserva). Código das rotas menos usadas (admin, página pública) é carregado sob demanda e medido à parte. Se algum PR estourar uma meta, o PR justifica e este ADR é atualizado, não ignorado.
8. **Sem regressão funcional** nos fluxos tocados (testes de front e a verificação em navegador).
A **suíte E2E crítica consolidada** vem **depois** da estabilização: agendamento completo (inclusive a partir da página pública), cancelamento e remarcação pelo cliente, check-in e fila pela equipe, e controle de acesso entre cliente e equipe (3 a 5 fluxos, sem automatizar cada detalhe visual).

## 9. Ambiente de desenvolvimento e verificação
Docker nem sempre está ligado, então o ambiente local usa um **servidor de desenvolvimento com PostgreSQL embutido** (o mesmo dos testes), com **dados fictícios de demonstração** (estúdio, equipe, clientes, pets, agendamentos em todos os estados) criados por um roteiro de carga; sobe a API e o Vite juntos. O Playwright usa esse ambiente. Nada disso vai para a produção, e as senhas são de teste.

## Plano (PRs pequenos, em ordem de dependência; cada um revisado pelo ChatGPT, com mutação onde houver comportamento)
| PR | Escopo | Esforço |
|---|---|---|
| R1 | Ambiente local com dados de demonstração + Playwright e axe + roteiro de capturas e de checagens + **medição do baseline e fixação dos orçamentos de desempenho** | ALTO |
| R2 | Fundação: tokens, fontes, tema claro e escuro (com alternância e sem flash), componentes base (Button, Field, Card, Badge, Notice). **Condição:** testados numa **página de demonstração isolada** (rota só de desenvolvimento, fora do build de produção), **sem migrar as telas atuais nem redesenhar o layout**: a substituição da estrutura visual é do R3 em diante | ALTO |
| R3 | Estrutura: barra lateral, gaveta no celular (com o ciclo de foco testado), menu por perfil, atalho para o conteúdo, títulos e foco por rota | ALTO |
| R4 | Entrada: entrar, cadastrar, trocar senha, 404, Início por perfil | MÉDIO |
| R5 | Agenda do dia e Fila com a Linha de Atendimento | ALTO |
| R6 | Agenda da semana (nova visão, com o comportamento por largura da seção 5) | MÉDIO |
| R7 | Cliente: Agendar, Meus agendamentos, Meus pets | MÉDIO |
| R8 | Prestador: Serviços, Expediente, Equipe, Meu negócio | MÉDIO |
| R9 | Página pública do estúdio e o endereço público no Meu negócio; agendar já no estúdio | MÉDIO |
| R10 | Admin | MÉDIO |
| R11 | Polimento: estados vazios e de erro, microtexto, auditoria final de acessibilidade e desempenho | MÉDIO |
| R12 | Suíte E2E crítica consolidada | ALTO |

## Riscos a atacar na revisão
Redesign tocando comportamento por engano (os testes de front são o freio); o peso de fontes e pacotes; o foco e a gaveta no celular; a semana em 7 colunas em telas médias; a página pública e o redirecionamento ao agendar já no estúdio; ícones sem nome acessível; contraste real no navegador diferente do medido; a "perfeição" como critério subjetivo (por isso os critérios mensuráveis e a aprovação visual do dono por capturas).
