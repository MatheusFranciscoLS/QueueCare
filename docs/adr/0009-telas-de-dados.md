# ADR 0009: padrões das telas que leem e gravam dados

- **Status:** Accepted
- **Data:** 2026-11-14
- **Quem decidiu:** o Matheus delegou; desenho de Claude revisado pelo ChatGPT (revisor independente).

## Contexto
As telas do prestador (negócio, serviços, expediente) são as primeiras que carregam e gravam dados da API. Os mesmos problemas aparecem em todas: resposta que chega fora de ordem, envio duplicado, erro de campo versus erro geral, estado "ainda não cadastrado" e editor que precisa refletir exatamente o que o servidor gravou.

## Decisão
1. **`useLoad`:** carrega ao abrir a tela e recarrega depois de gravar. Uma resposta que chega depois de a tela fechar ou depois de um novo carregamento é DESCARTADA (contador de execuções), então o dado mais novo nunca é sobrescrito por um mais antigo. **Falha ao ATUALIZAR** uma tela que já tinha dados não apaga a tela nem passa em silêncio: os dados antigos continuam, aparece o aviso "pode estar desatualizado" com "Tentar de novo" (`RefreshWarning`). Cada carregamento bem-sucedido sobe uma **versão** (usada como chave de editores).
2. **Um envio por vez** (`useSingleFlight`) em todo formulário e em toda ação que grava.
3. **A API manda:** o front valida só para avisar cedo (mesmos limites da API); recusas da API aparecem no campo certo (`errors` do problem+json); o aviso geral só aparece quando não há campo a apontar, para o mesmo texto não aparecer duas vezes. Mensagens vêm de códigos estáveis, nunca do detalhe bruto.
4. **Negócio ainda não cadastrado** (404 `PROVIDER_NOT_CONFIGURED`) não é erro: a tela convida a cadastrar (negócio) ou manda cadastrar primeiro (serviços e expediente).
5. **Expediente substitui o conjunto COMPLETO** (como a API): o editor guarda o conjunto em memória, valida (início antes do fim, sem sobreposição no mesmo dia, janelas coladas aceitas, no máximo 70) e envia tudo; lista vazia significa "não atende" e a tela avisa. Durante a gravação E durante o carregamento que vem logo depois dela TODOS os controles do editor ficam desabilitados (campos, adicionar, remover, salvar): `reload()` devolve uma promessa que termina quando o carregamento termina OU assim que ele é substituído por outro mais novo ou a tela fecha, sem esperar a resposta antiga (nunca rejeita, nem se a função de carga lançar de forma síncrona) e a tela espera por ela antes de liberar, então não há janela em que uma edição possa se perder. O mesmo vale para o formulário do negócio (também refeito por versão a cada carregamento bem-sucedido, para mostrar o que o SERVIDOR devolveu, com o aviso "salvo" na tela-mãe) e para as linhas de serviço (o botão "Editar" fica desabilitado até o GET terminar e, ao abrir a edição, os campos partem dos valores atuais devolvidos pelo servidor). Depois de salvar, o editor é refeito a partir do que o servidor devolveu a cada carregamento bem-sucedido (a chave é a versão, não o conteúdo, para também refletir um conjunto idêntico ao anterior), e o aviso "salvo" fica na tela-mãe para não sumir nessa troca. Erro de um dia liga a mensagem aos campos de hora (`aria-invalid`, `aria-describedby`) e leva o foco ao primeiro inválido.
6. **Telas por perfil:** `RequireRole` leva quem tem outro perfil ao início e o menu só aparece para o prestador. É conforto de navegação; a API continua autorizando (ADR 0005).
7. **Horário local:** o expediente é sempre no fuso do negócio (escolhido numa lista do navegador, o do Brasil primeiro; a API confere). A API devolve `08:00:00` e aceita `08:00`; o front trabalha com `HH:mm`.

## Alternativas descartadas
- **Biblioteca de cache de dados (React Query):** mais conceitos do que três telas pedem; reavaliar com muitas listas.
- **Salvar cada janela do expediente separadamente:** a API só tem a substituição do conjunto, que evita estados intermediários inválidos e corridas.

## Consequências e limitações conhecidas
- Um rascunho de serviço em edição não é apagado quando a lista recarrega por outra ação (cada linha guarda o seu estado). Dois dispositivos editando o expediente: vale o último a salvar (a API serializa); a tela não avisa que o outro mudou.
- Não há prévia de "como o cliente vê" os horários livres (vem com a tela de reserva).
