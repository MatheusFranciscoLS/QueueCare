# Redesign R3: estrutura e navegação (estudo)

Parte do ADR 0018 v2. A partir deste PR o produto **muda de cara**: a casca de todas as telas é nova.

## O que entra
- **Barra lateral** só de texto no computador (232 px); o item ativo ganha um trecho de fio cheio. No celular (até 900 px), barra superior com a marca e o botão **Menu**, que abre uma **gaveta** (`<dialog>` nativo: foco inicial e contido, fundo inerte, Esc fecha, foco volta ao botão).
- **Menu por perfil** (`pages/navigation.ts`): PROVIDER (Início, Agenda, Fila, Serviços, Expediente, Equipe, Meu negócio), ATTENDANT (Início, Agenda, Fila), CUSTOMER (Início, Agendar, Meus agendamentos, Meus pets), ADMIN (Contas, Novo prestador). Os nomes dos menus (`Menu do prestador`...) seguem os mesmos para leitores de tela e testes.
- **Atalho para o conteúdo**, marcos (`header`, `nav`, `main`), **título da página por rota** e **foco no título (h1) quando a rota muda** (`useRouteFocus`: observa o conteúdo até o h1 existir, no máximo 5 s; na primeira carga não move o foco).
- **Conta** no rodapé da barra: nome, perfil, "Trocar senha", tema (sistema, claro, escuro) e "Sair".
- **Ponte de variáveis** (`ui/shell.css`): as telas ainda não migradas usam as cores, as fontes e o tema novos sem mudar uma linha; sai tela a tela (R4 a R10). As fontes antigas (Inter e Manrope) foram **removidas**: o peso de fontes cai de 73 para ~75 kB só das novas, dentro do limite de 80 kB.

## Evidências
- Testes: 357 (11 novos em `shell.test.tsx`: navegação por perfil, atalho, título e foco por rota, conta, gaveta). Os 346 anteriores passam sem alteração, exceto o nome da marca.
- **Gaveta em navegador real** (`e2e/gaveta.spec.ts`): foco inicial dentro, Tab e Shift+Tab nunca chegam ao conteúdo por trás, o conteúdo não é clicável por trás, Esc fecha e devolve o foco ao Menu, escolher um item fecha a gaveta e leva o foco ao título com o título da página certo.
- **Verificação estrita em Edge, 68 combinações (17 telas, 2 janelas, 2 temas):** axe graves/críticas **0** em todas; rolagem horizontal **0** em todas (a linha de base tinha 44 larguras com rolagem); nenhum achado vem da casca nova. Restam, **só dentro do conteúdo antigo**, links curtos menores que 44 px ("Cadastre-se como cliente", "Entrar", atalhos do Início, "Abrir a agenda"), os campos de horário do Expediente (anel cortado na borda esquerda) e uma caixa de marcar de Meus pets: cada um é tratado no PR de migração da própria tela (R4, R7, R8).
- Peso: JS inicial 105,6 kB gzip (meta 130), fontes só as novas.

## Limite honesto
No celular o foco da gaveta é "contido" no sentido do documento (dentro dela ou fora, na barra do navegador): o navegador leva o Tab seguinte à barra de endereço e de volta, nunca ao conteúdo por trás.
