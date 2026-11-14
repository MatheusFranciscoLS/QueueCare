# Estudo: listagem de contas para o ADMIN (PR 28, backend)

## Em linguagem simples
Para ativar, desativar ou redefinir a senha de alguém, o administrador precisa ENCONTRAR a conta. Esta rota lista as contas com busca e filtro, sem nunca mostrar senha.

| Chamada | Quem pode | Resultado |
|---|---|---|
| `GET /api/admin/users?role=&q=&page=&size=` | só ADMIN | lista de contas (id, nome, e-mail, perfil, ativo, precisa trocar senha, criada em) |

## Regras
- Perfis PROVIDER, ATTENDANT e CUSTOMER recebem 403; sem token, 401.
- Nunca devolve senha, hash nem a versão das credenciais.
- `role` filtra por perfil (valor inexistente: 400); `q` procura no nome ou no e-mail sem diferenciar maiúsculas.
- Os curingas digitados (`%`, `_`) valem como texto comum: buscar `ana_silva` não acha `anaxsilva`.
- `size` de 1 a 50, `page` a partir de 0, `q` até 100 caracteres (fora disso, 400). Ordem: e-mail e depois id, para a paginação não repetir nem perder ninguém.

## Como ler o código
`AdminUserController.list`, `AdminUserService.list` (escape do padrão e perfis permitidos) e `UserAccountRepository.search`; teste `AdminUserListIntegrationTest` (7 testes, HTTP e PostgreSQL reais).

## Três perguntas de entrevista
1. **Por que escapar `%` e `_` na busca?** Sem isso, quem digita `%` listaria todo mundo e `_` casaria qualquer caractere: a busca viraria um padrão, não texto.
2. **Por que ordenar por e-mail e depois por id?** O e-mail pode empatar por caixa; o id fecha o empate e deixa a paginação estável.
3. **Por que a listagem não é auditada?** Auditamos mudanças (ativar, desativar, redefinir); leitura não altera nada e a rota já é restrita ao ADMIN.

## Limitações
- Sem contagem total nem "próxima página" (a tela pede mais enquanto vier a página cheia); sem busca por data.
