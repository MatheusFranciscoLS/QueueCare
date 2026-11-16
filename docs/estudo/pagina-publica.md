# Estudo: página pública do estúdio

## O que é
Um endereço compartilhável (`/p/meu-estudio` na tela, `GET /api/public/studios/meu-estudio` na API) que mostra nome, fuso e serviços do estúdio sem login. O dono escolhe o endereço e decide quando publicar.

## Decisões que valem entrevista
- **Publicar é explícito** (`published`, falso por padrão): nada vaza só por existir um endereço.
- **Mesma resposta para tudo que "não existe"** (inexistente, não publicado, dono inativo, formato inválido): quem consulta não descobre quais endereços existem.
- **O banco é a última palavra:** formato (CHECK), unicidade (índice único parcial) e "publicado exige endereço" (CHECK). A corrida entre dois estúdios pelo mesmo endereço termina com 200 e 409, não com dado corrompido.
- **Lista fechada de campos** na resposta pública, com teste que compara o conjunto exato de campos (um campo novo aparece como falha, não como vazamento silencioso).
- **Limite de requisições** por endereço de rede, sem login.

## Testes
`PublicStudioApiIntegrationTest` (9 casos, PostgreSQL real, inclusive a corrida) e `RateLimitEndpointsIntegrationTest` (limite da rota pública). Mutação verificada: remover o filtro `published = true` derruba um teste.

## Perguntas de entrevista
1. Por que 404 igual para "não publicado" e "inexistente" em vez de 403?
2. Por que validar o endereço na aplicação **e** no banco?
3. O que muda (riscos) se a reserva puder ser feita sem conta?
