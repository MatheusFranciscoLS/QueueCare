# ADR 0034: importação de clientes por planilha

- **Status:** Accepted (implementado em 24/11/2026)
- **Data:** 24/11/2026
- **Esforço:** MÉDIO (um serviço, uma rota, um leitor de CSV, um painel, 8 testes de integração e 14 de tela)

## Contexto: a análise crítica
Um estúdio que começa a usar o sistema já tem os clientes: num caderno, no Excel ou no contatos do WhatsApp. Sem importar, o dono digita um por um, e a maioria desiste na décima pessoa. Era o último item da lista de prioridades de uso real, e só fez sentido **depois** do cadastro pelo balcão (ADR 0033): a importação **cria tutores do balcão**, com a mesma fronteira de segurança, em vez de inventar um segundo tipo de cadastro.

## Decisão
1. **O arquivo é lido no navegador.** A tela lê o CSV, acha as colunas, confere cada linha e mostra **antes de importar** quantas estão prontas, quais têm problema (com o número da linha) e quais pets ficam de fora. Só as linhas já conferidas sobem, em blocos de 200 (até 1000 por vez). Não há upload de arquivo, nem limite de tamanho de arquivo no servidor, nem biblioteca de planilha.
2. **O CSV do Excel brasileiro é tratado**: separador `;` (ou vírgula, ou tabulação, o mais frequente na primeira linha), aspas e quebras de linha dentro de aspas, e **Windows-1252**: o "CSV" do Excel sem marca costuma vir assim, e abrir como UTF-8 transforma "Conceição" em lixo. A leitura tenta UTF-8 estrito e cai para Windows-1252 (há teste com os bytes reais e um fluxo no navegador com acentos).
3. **Colunas pelo nome do cabeçalho**, em qualquer ordem, sem acento nem maiúscula: Nome (ou Cliente, Tutor) e Telefone (ou Celular, WhatsApp) são obrigatórias; E-mail, Pet e Espécie são opcionais. Falta de coluna obrigatória diz qual falta e não deixa importar. Há um **modelo** para baixar.
4. **Só o dono importa** (cria muitas contas de uma vez), e a API recusa o resto: até **200 linhas por chamada** e **5 importações por hora por estúdio** (429), contado antes de qualquer conferência.
5. **Cada linha é independente** (uma transação por linha): a ruim é relatada, as outras entram. Importar o mesmo arquivo de novo é seguro, e é o que a tela orienta se a rede cair no meio.
6. **Nunca duplica.** Telefone que o estúdio já tem (atendido ou cadastrado pelo balcão) é **pulado**, e a repetição dentro do próprio arquivo também (com ou sem máscara). Telefone de **outro** estúdio não conta: cada estúdio só enxerga os seus.
7. **Nunca adivinha espécie.** O pet só é cadastrado com espécie reconhecida (cachorro, cão, gato...); sem ela o tutor entra e o pet fica de fora, e a tela avisa **antes**. Chutar "cachorro" gravaria um dado errado numa ficha de saúde.
8. **A resposta nunca devolve dado pessoal.** Diz o número da linha e o motivo (criado, telefone repetido, inválido, e-mail já no app), nunca o nome, o telefone ou o e-mail que vieram na planilha.
9. **E-mail que já tem conta no app** não é importado (409 uniforme por dentro, aqui "e-mail já no app"): o tutor já agenda sozinho, e a importação não pode revelar nem tocar uma conta dele.
10. **Auditoria**: cada tutor criado grava `CUSTOMER_CREATED_BY_STUDIO`, como no cadastro um a um. Quem foi importado **não tem acesso ao app**, e os direitos de privacidade são os do ADR 0033 (o dono baixa os dados ou exclui o cadastro a pedido).

## O que foi descartado
- **Enviar o arquivo ao servidor:** mais superfície (tamanho, tipo, antivírus, memória no plano gratuito) para nada; a tela já tem o que precisa para conferir.
- **Biblioteca de leitura de .xlsx:** dependência grande para um formato que o Excel exporta como CSV em dois cliques; o modelo e a instrução cobrem.
- **Atualizar cadastro existente pelo telefone:** misturaria dado novo com dado que o estúdio pode ter corrigido à mão. A importação só cria; quem existe é pulado.
- **Importar contatos do celular:** exige permissão e integração que não são custo zero nem do navegador.
- **Enviar mensagem de boas-vindas aos importados:** a pessoa não pediu para receber, e o envio automático é vetado pelo ADR 0013.

## Riscos conhecidos
- **Importar a lista errada.** Não há "desfazer importação": o dono exclui cadastro por cadastro (ADR 0033). A conferência antes de importar e a ausência de acesso ao app limitam o estrago; um "desfazer o último lote" é um bom próximo passo se aparecer na prática.
- **Base legal.** Os importados não pediram nada ao sistema; a base é a relação de atendimento que o estúdio já tinha com eles. É responsabilidade do dono só importar quem é cliente dele; a Política de Privacidade (ADR 0033) diz que o estúdio registra os dados e que o titular pode pedir a cópia ou a exclusão.
- **Telefone compartilhado** (família) é pulado como repetido: o dono cadastra o segundo pelo botão "Novo cliente", que pergunta antes de duplicar.
- **Limite de 1000 linhas por vez** (5 blocos, o máximo por hora); estúdios maiores importam em mais de um arquivo, em horas diferentes.

## Consequências
- Em poucos minutos o estúdio sai de zero para a lista inteira de clientes, pronta para agendar pelo balcão.
- Nenhuma dependência nova, nenhum arquivo sobe, e a rota nova é só do dono e tem limite.
