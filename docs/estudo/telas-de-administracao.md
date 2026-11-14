# Estudo: telas de administração (PR 29, front do ADMIN)

## Em uma frase
O administrador encontra uma conta, ativa, desativa ou redefine a senha dela, e cria a conta de um prestador junto com o negócio, sem nunca ver senha de ninguém além da que ele mesmo acabou de definir.

## O que existe
- **Contas** (`/admin/contas`): busca por nome ou e-mail, filtro por perfil, páginas de 20, situação (Ativa, Inativa, Precisa trocar a senha), **Desativar** (com confirmação), **Ativar**, **Redefinir senha** (digitar ou "Gerar senha").
- **Novo prestador** (`/admin/prestadores/novo`): nome, e-mail, senha provisória, negócio, fuso e prazos, em uma só criação.
- Menu do administrador; só ADMIN entra (a API confere de novo).

## Como ler o código
1. `api/admin.ts` (chamadas) e `api/messages.ts` (motivos novos).
2. `pages/admin/UsersPage.tsx` (busca, lista, linha), `pages/admin/CreateProviderPage.tsx` e `pages/admin/PasswordDelivery.tsx` (entrega única da senha).

## Provas por teste (236 no front, 25 novos em `admin.test.tsx`)
- Lista: campos, a própria conta sem "Desativar", busca com a consulta certa, lista de outra busca nunca sob a nova, vazio, erro ao carregar, paginação e volta à página 0 ao buscar.
- Desativar/ativar: confirmação com foco em "Voltar", situação na hora mesmo se o GET falhar, leitura nova bem-sucedida manda, `LAST_ACTIVE_ADMIN`, 404 com o motivo mantido mesmo com a linha removida, duplo clique.
- Redefinir: campo com foco, senha curta recusada, a senha ENVIADA é a mostrada (uma vez, some ao limpar), copiar entrega a mesma senha, falha nunca vira "entregue", duplo clique.
- Novo prestador: validação com foco, corpo enviado exato, senha uma vez, 409 uniforme sem senha, erros de campo, duplo clique.
- Perfis: PROVIDER, ATTENDANT e CUSTOMER não veem o menu nem chamam a administração.
- Mutações verificadas: sem a confirmação local, "Desativar" na própria conta, sem voltar à página 0 e sem a chave da consulta: cada uma derruba o seu teste.

## Três perguntas de entrevista
1. **Por que a senha só aparece depois do sucesso, e uma vez?** Mostrar antes poderia apresentar como "entregue" uma senha que a API recusou; e guardá-la depois seria um segredo vivo na tela. Fica só em memória e some ao limpar.
2. **Por que a situação confirmada na tela só vale até a próxima leitura bem-sucedida?** Se o GET falha, a tela não pode voltar atrás; se ele funciona, é o retrato mais novo do servidor (outro administrador pode ter mudado).
3. **Por que a própria conta não tem "Desativar" se a API já recusa?** A API é a autoridade, mas oferecer um botão que só dá erro é má experiência; a tela esconde e a API continua protegendo.

## Limitações
- Paginação por deslocamento (ver ADR 0007); sem filtro por situação (ativa/inativa); sem histórico de auditoria na tela.
