# ADR 0015: página pública do estúdio

- **Status:** Accepted
- **Data:** 16/11/2026
- **Quem decidiu:** o Matheus aprovou o nicho (banho e tosa) e a marca **Pelo & Ponto**; o ChatGPT classificou esta tarefa como esforço MÉDIO e pediu um ADR com política de publicação, unicidade e formato do endereço, dados expostos e comportamento para estúdio inexistente ou não publicado.

## Contexto
Todo o sistema exigia login. Um estúdio precisa de um link para compartilhar (rede social, WhatsApp) que mostre quem ele é e o que oferece, sem criar conta.

## Decisão
1. **Endereço público (slug)** no próprio negócio: `providers.slug` (único, minúsculas, 3 a 40 caracteres, letras, números e hífens entre grupos) e `providers.published` (falso por padrão). A migration V7 garante no banco: formato, unicidade (índice único parcial) e "publicado exige endereço".
2. **Quem configura:** só o dono (`PROVIDER`), por `GET/PUT /api/provider/public-page`; o negócio vem sempre do token. Endereços **reservados** (rotas do app, "admin", "api", a marca etc.) são recusados. Conflito de endereço: **409 `SLUG_TAKEN`**, decidido pelo banco mesmo com dois pedidos simultâneos.
3. **Leitura pública:** `GET /api/public/studios/{slug}`, **sem login**, somente leitura. Devolve apenas `slug`, `businessName`, `timeZone` e a lista de **serviços ativos** (`name`, `durationMinutes`). **Não expõe:** ids, e-mail ou dados do dono, equipe, configurações (antecedência, cancelamento), agenda, clientes, serviços desativados.
4. **Ausência uniforme:** endereço inválido, inexistente, não publicado ou de dono desativado respondem **o mesmo 404 `STUDIO_NOT_FOUND`**, sem revelar quais endereços existem.
5. **Abuso:** limite por minuto por endereço de rede (a mesma configuração dos horários livres), com `429` e `Retry-After`; as respostas de erro não repetem dados.
6. **Fora de escopo agora:** reservar sem conta (o agendamento continua exigindo login de cliente), preço, fotos, endereço físico, SEO/Open Graph (precisa de renderização no servidor; avaliar depois), domínio próprio.

## Consequências
- O link público mostra o estúdio, mas quem quer agendar entra ou se cadastra como cliente (decisão consciente: reserva sem conta é outro ADR, com mais riscos de abuso).
- Mudar o endereço invalida o link antigo (sem redirecionamento). Aceito no MVP.
- A grafia da marca em textos públicos é "Pelo & Ponto"; o `&` não entra no endereço (`pelo-e-ponto` é reservado).

## Atualização (revisão do PR 44)
- **Limite por endereço de rede, sempre:** na rota pública a chave ignora o token (`public|ip:<endereço>`); trocar de token não dá limite novo. Teste com mutação.
- **Proxy do Render:** `server.forward-headers-strategy: native` no perfil de produção (travado por teste de arquivos de publicação). Sem isso o endereço visto pela API seria o do proxy e todos os visitantes dividiriam um limite (valia também para login e cadastro). O Tomcat só confia em proxies internos (faixas privadas) e usa o endereço do cliente anexado pelo proxy. **Pendente de conferência após o deploy:** dois acessos de redes diferentes não podem dividir o limite; se o Render não anexar o cabeçalho como esperado, reavaliar (ADR 0003).
- **Conflito de endereço só para o índice certo:** `SLUG_TAKEN` é devolvido somente quando a violação é do índice `providers_slug_key`; outra violação de integridade continua sendo erro de verdade.
- Respostas de erro da rota pública e da configuração declaram o schema `Problem` no contrato.
