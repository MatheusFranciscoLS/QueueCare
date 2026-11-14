# Estudo: telas do prestador (PR 21)

## Em uma frase
O prestador já configura sozinho o negócio, os serviços e o expediente pela interface, e a tela nunca diz "salvo" quando não salvou.

## O que existe
- **Meu negócio:** cadastra ou edita nome, fuso, antecedência mínima e prazo de cancelamento.
- **Serviços:** lista, cria, edita e desativa/reativa (duração de 5 a 480 minutos).
- **Expediente:** editor por dia da semana com vários horários, validação antes de enviar e envio do conjunto completo.
- **Menu e início do prestador**, com a ordem sugerida de configuração; acesso só do perfil PROVIDER.

## Como ler o código
1. `api/provider.ts`: as chamadas; `api/schedule.ts`: a lógica pura do expediente.
2. `pages/useLoad.ts` e `pages/useSingleFlight.ts`: os padrões compartilhados.
3. `pages/provider/*`: as três telas.

## Provas por teste (107 no total; 80 novos)
- Expediente (lógica pura): conversões de hora, ordenação, vazio, janelas coladas aceitas, sobreposição (inclusive fora de ordem e uma dentro da outra), limite de 70.
- Negócio: cadastro sem negócio, edição, erro de campo da API no campo certo, validação no navegador, falha do servidor.
- Serviços: criar, editar e desativar, validação, convite para cadastrar o negócio primeiro.
- Expediente (tela): mostra o gravado, salva o conjunto completo, não envia sobreposição, lista vazia envia `[]`, erro do servidor não vira "salvo".
- Perfis: só o prestador vê o menu; CUSTOMER, ATTENDANT e ADMIN digitando as URLs do prestador voltam ao início sem nenhuma chamada à API.
- `useLoad`: resposta antiga nunca sobrescreve a nova, resposta depois de desmontar é descartada, recarregar depois de gravar respeita a ordem, falha ao atualizar mantém o dado e expõe o erro, versão sobe só em sucesso.
- Atualização que falha depois de gravar (serviços, expediente e negócio): dado antigo visível, aviso e tentar de novo.
- Expediente durante a gravação: controles bloqueados; reflete o que o servidor devolveu (diferente do enviado ou idêntico); 70 horários salvam e o 71º não pode ser adicionado; erros por dia ligados aos campos com foco.
- Serviços: erros de campo sem duplicar no aviso geral; rascunho preservado durante recarregamento.
- A janela entre o fim da gravação e o fim do GET seguinte: controles continuam bloqueados até o GET terminar (sucesso ou falha) e depois mostram o que o servidor devolveu; no negócio, os valores do servidor substituem os digitados.
- Serviços: "Editar" bloqueado até o GET terminar e campos a partir do que o servidor devolveu; campos, Salvar e Cancelar (edição) e campos do novo serviço bloqueados durante a gravação.
- `useLoad`: promessa de um carregamento substituído termina na hora; exceção síncrona vira erro de carregamento; fechar a tela encerra a promessa.
- Mutações: sem o bloqueio dos controles, sem o aviso de atualização, sem esperar o GET posterior e sem encerrar o carregamento substituído, os testes falham.

## Três perguntas de entrevista
1. **Por que o expediente envia o conjunto completo em vez de uma janela por vez?** A API substitui tudo de forma atômica; janelas isoladas permitiriam estados intermediários inválidos e corridas.
2. **Como a tela evita que uma resposta antiga sobrescreva uma nova?** Cada carregamento tem um número; só a resposta do número mais recente é aplicada.
3. **Por que o aviso geral de erro some quando há erro de campo?** Para a mesma mensagem não aparecer duas vezes e o foco ir para o campo certo.

## Limitações e o que falta
- Equipe e agenda do dia (próximo PR); reserva pelo cliente depende do endpoint de listar prestadores.
- Sem teste com navegador real ainda (Playwright entra depois).
