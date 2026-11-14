# ADR 0008: base do front-end (React, sessão e rotas)

- **Status:** Accepted
- **Data:** 2026-11-14
- **Quem decidiu:** o Matheus delegou; desenho de Claude revisado pelo ChatGPT (revisor independente).

## Contexto
A API está pronta e testada (agendamento, contas, senhas, equipe). Falta a interface. A primeira decisão é onde guardar o token, como organizar sessão, rotas e erros, e como testar telas sem depender do servidor.

## Decisão
1. **Pilha:** React 19 + TypeScript + Vite; `react-router-dom` para rotas; CSS próprio com variáveis (tema claro e escuro, segue o sistema), sem biblioteca de componentes. Testes com Vitest + Testing Library + jsdom. Nada de gerenciador de estado global: um contexto de sessão e estado local nas telas bastam por enquanto.
2. **O token (JWT de 15 minutos) fica SÓ em memória** (estado do React e uma referência): nunca em `localStorage`, `sessionStorage` nem cookie. Um script injetado (XSS) não encontra o token guardado para roubar depois. Custo aceito: recarregar a página pede o login de novo. Testes conferem que nenhum armazenamento do navegador recebe o token. Evolução natural, quando houver refresh token: cookie HttpOnly com SameSite, o que exigirá reavaliar CSRF (hoje desligado por não haver cookie, ADR 0003).
3. **Um único cliente HTTP** (`api/client.ts`) anexa o `Authorization: Bearer`, usa `credentials: omit` e converte `application/problem+json` em `ApiError` (status, código estável `reason`, erros por campo, `Retry-After`). As mensagens para a pessoa vêm de `api/messages.ts`, a partir dos códigos estáveis, e **nunca repetem dados digitados**.
4. **Sessão:** `AuthProvider` guarda o usuário (da rota `/api/me`) e o token. A sessão termina sozinha pouco antes do token vencer (aviso na tela de entrada) e **qualquer resposta 401** a uma chamada autenticada encerra a sessão (token vencido, conta desativada ou senha trocada em outro aparelho), mas só se o 401 for do token VIGENTE: uma resposta atrasada da sessão antiga não derruba a nova. Cada operação de autenticação (entrar, cadastrar, trocar senha) e cada encerramento incrementam uma **geração**; uma operação que termina depois de a geração mudar (por exemplo, a consulta a `/api/me` que chega depois de um "Sair") **não reabre a sessão**.
5. **Troca de senha obrigatória é imposta nas rotas:** conta com `mustChangePassword` só enxerga a tela de troca; a API também recusa o resto (ADR 0005). A troca usa o token NOVO devolvido pela API, já que o anterior deixa de valer (ADR 0006).
6. **Destino depois do login só entre rotas internas conhecidas** (lista explícita: `/` e `/trocar-senha`); URL absoluta, `//host` ou rota desconhecida viram `/`. Mensagens de erro vêm só de códigos estáveis e status: o `detail` bruto da API nunca é exibido. Um segundo envio durante o primeiro é ignorado.
7. **Validação no navegador é conforto, não segurança:** a política de senha é repetida no front só para avisar cedo; a API é quem decide e suas recusas são mostradas no campo certo.
8. **Acessibilidade desde o início:** todo campo tem rótulo ligado, dica e erro ligados por `aria-describedby`, erros de formulário em `role="alert"`, foco visível (e, depois de uma submissão com erro, no primeiro campo inválido), erro do campo some quando a pessoa o corrige, botão desabilitado só quando falta dado.

## Alternativas descartadas
- **Token em `localStorage`:** sobrevive ao recarregamento, mas qualquer XSS o lê.
- **Cookie HttpOnly agora:** exigiria refresh token no servidor e rever CSRF; fica para quando o recarregamento sem login incomodar de verdade.
- **Redux/Zustand/React Query:** complexidade sem necessidade nas telas atuais; reavaliar quando houver muitas listas com cache e revalidação.
- **Biblioteca de componentes:** peso e aparência genérica; o conjunto de telas é pequeno.

## Consequências e limitações conhecidas
- Recarregar a página encerra a sessão.
- O início de cada perfil ainda lista o que vem a seguir (as telas chegam nos próximos PRs).
- Faltam endpoints de leitura para algumas telas (listar prestadores para o cliente, listar usuários para o ADMIN, dados do prestador/serviço nos agendamentos); entram junto de cada tela.

## Atualização (14/11/2026): publicação em endereços separados (ADR 0013)
- **Endereço da API por configuração:** `VITE_API_URL` (definida na hora do build; vazia em desenvolvimento, quando o Vite redireciona `/api`). `apiUrl()` aceita só `http(s)://origem` SEM usuário/senha embutidos, caminho, parâmetros nem âncora; qualquer outro valor é erro de configuração que FALHA ALTO (nunca manda o token Bearer para um lugar errado). O token continua só em memória e a API continua sem cookies.
- **Aviso "servidor acordando":** toda requisição pendente por mais de 4 s conta como lenta e mostra uma faixa amigável (role status) até a resposta chegar ou falhar; cada requisição conta uma vez só. Serve para a hospedagem gratuita que dorme sem uso (cold start de cerca de um minuto).
