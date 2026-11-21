# ADR 0027: LGPD mínima — política, exportar e excluir a conta

- **Status:** Accepted (implementado em 21/11/2026, quarto item do plano de prontidão para uso real)
- **Data:** 21/11/2026
- **Esforço:** MÉDIO (uma migração, um serviço, duas rotas, uma página pública e uma seção do perfil)

## Contexto: a análise crítica
O app guarda dados de pessoas (nome, e-mail, telefone) e de pets, inclusive observações de saúde e comportamento. A Lei Geral de Proteção de Dados pede transparência (a pessoa sabe o que se guarda e para quê) e **direitos que se exercem**: acesso, portabilidade, correção e eliminação. Uma política escrita sem um caminho dentro do app para exercê-los é só texto; um botão "excluir conta" que apaga a linha do banco quebra agendamentos e auditoria. As duas pontas precisam existir e funcionar.

## Decisão
1. **Política de Privacidade pública** em `/privacidade`, em linguagem simples e específica deste app: o que guarda, para quê, com quem passa (o estúdio onde a pessoa agenda; a hospedagem; o WhatsApp só quando a equipe toca em "Lembrar"), por quanto tempo, quais são os direitos e como exercê-los, e **o que o app não faz** (sem rastreio, anúncios ou venda de dados). Tem **versão** (`AAAA-MM-DD`) e avisa que é apoio técnico, não parecer jurídico.
2. **Registro de leitura, não "consentimento universal".** O cadastro mostra "Ao criar a conta, você confirma que leu a Política de Privacidade" e grava **a versão e o instante** (`users.privacy_policy_version` e `privacy_accepted_at`; o banco exige os dois juntos ou nenhum). Foi de propósito que não existe um checkbox genérico "aceito a LGPD": a base legal de cada tratamento não é, em geral, consentimento, e um checkbox só finge que é.
3. **Exportar** (`GET /api/me/export`, só o tutor): um JSON com a conta, os pets, os agendamentos (estúdio, serviço, horário, estado, valor) e a lista de espera, sem hash de senha, sem dado de outra pessoa, `Cache-Control: no-store` e como arquivo (`meus-dados.json`). Cobre acesso e portabilidade.
4. **Excluir = anonimizar no lugar** (`POST /api/me/delete-account`, só o tutor, com a senha atual e limite de 5 tentativas):
   - **Sai** o que identifica: nome, e-mail, telefone, senha (vira um hash que ninguém conhece), e os dados dos pets, inclusive a **cópia gravada em cada agendamento**. A conta fica inativa e todo token deixa de valer.
   - **Cancela** os agendamentos ainda marcados (o estúdio vê o horário voltar, com o motivo no histórico) e **esvazia** a lista de espera.
   - **Fica** o que é registro do estúdio e não identifica ninguém: dia, serviço e valor dos atendimentos feitos.
   - **Auditoria:** `ACCOUNT_ANONYMIZED` (ator = alvo = o tutor).
   - O e-mail original fica livre: a pessoa pode voltar com uma conta nova.
5. **Só o tutor.** Dono, atendente e administrador têm contas de trabalho, tratadas pelo administrador (ADR 0005 e 0007).

## Por que anonimizar em vez de apagar a linha
Agendamentos (registro operacional do estúdio, inclusive financeiro) e a auditoria apontam para o usuário por chave estrangeira, e a auditoria é somente de acréscimo por desenho (ADR 0005). Apagar a linha obrigaria a apagar também o histórico do estúdio, que não é do tutor. Anonimizar atende ao direito de eliminação sem destruir o que pertence a outro.

## Fora de escopo e riscos conhecidos
- **Backups:** os dados anonimizados continuam nas cópias antigas até elas expirarem (ver a estratégia de backup, próximo item do plano).
- **Logs do provedor de hospedagem** têm retenção própria, fora do controle do app.
- **Retenção por prazo** (apagar contas inativas depois de N anos) não é automática: documentada em `docs/privacidade-dados.md`, sem rotina agendada.
- **Pedido por outro canal** (o titular escreve ao estúdio): o estúdio não tem hoje um botão para excluir a conta de um tutor; o tutor mesmo o faz em Meu perfil.
- **Bases legais e texto final** pedem revisão jurídica antes de qualquer uso comercial.

## Consequências
- O tutor vê, baixa e exclui o que o app sabe dele, sozinho, em um minuto.
- O registro da versão lida permite provar qual texto estava no ar quando a pessoa se cadastrou.
- Testes: 8 de integração (aceite, formato, exportação sem credenciais nem dado alheio, anonimização completa, token e login, e-mail reutilizável, senha errada, limite de tentativas, papéis) e 7 de tela.
