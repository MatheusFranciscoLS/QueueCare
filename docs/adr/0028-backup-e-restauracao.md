# ADR 0028: backup diário criptografado e restauração ensaiada

- **Status:** Accepted (implementado em 21/11/2026, quinto item do plano de prontidão para uso real)
- **Data:** 21/11/2026
- **Esforço:** MÉDIO (três scripts, dois workflows, documentação)

## Contexto: a análise crítica
Banco na nuvem não é backup. O plano gratuito do Render Postgres expira e não tem cópia; o Neon tem recursos de restauração, mas no mesmo provedor e com limites que mudam. Se o banco for apagado, corrompido ou a conta for suspensa, o estúdio perde agenda, pets, clientes e histórico. E um backup **que nunca foi restaurado** é uma esperança: o dia em que importa é o pior para descobrir que o arquivo não abre.

Restrições: **custo zero**, nenhum segredo obrigatório (o projeto é de portfólio) e o repositório é **público**, e o artefato de um workflow de repositório público pode ser baixado por qualquer conta do GitHub.

## Decisão
1. **`pg_dump` em formato custom** (já comprimido, restaurável por tabela), com `--no-owner --no-privileges` para restaurar em qualquer banco.
2. **Sempre criptografado**: GPG simétrico AES-256. O script **recusa** gerar sem frase-senha ou com frase de menos de 16 caracteres. A frase entra por descritor de arquivo, não por argumento (que apareceria na lista de processos), e nada sensível é impresso.
3. **Soma de verificação** do arquivo criptografado, conferida antes de restaurar (pega download truncado ou corrompido).
4. **Restauração segura por ordem**: confere a soma, decifra para pasta temporária (apagada ao sair), lista o dump (`pg_restore --list`) **antes de tocar no destino**, e só então restaura, parando no primeiro erro. Exige `CONFIRM_RESTORE=yes`, porque sobrescreve o destino.
5. **O ensaio roda na CI a cada mudança**, não "de vez em quando": aplica **todas as migrações** num PostgreSQL de verdade, planta dados fictícios em várias tabelas (acentos, emoji, chaves estrangeiras, restrições), faz o backup, restaura num **segundo** PostgreSQL e compara tabelas, contagens e impressão digital. Também prova as recusas: sem confirmação, frase errada, arquivo corrompido (e que o destino continua intacto depois delas) e que backup sem criptografia é impossível. Assim, mexer numa migração de um jeito que quebra a restauração falha no PR.
6. **Backup de produção** (`backup.yml`; **era agendado diário às 06:17 UTC e, desde 24/11/2026, é só sob demanda**, porque sem segredos o job diário era ruído; o ensaio de restauração segue em cada PR): **só roda se o dono cadastrar** `BACKUP_DATABASE_URL` e `BACKUP_PASSPHRASE`; sem eles termina em sucesso, sem fazer nada. Cada backup é **restaurado num banco descartável** e conferido (`verify.sh smoke`) antes de ser guardado como artefato por 14 dias. Se a prova falha, o job fica vermelho e o backup do dia não vale.
7. **Metas declaradas** (RPO 24 h SE houver um backup por dia; hoje o RPO é o tempo desde o último backup manual, RTO cerca de 1 h) e procedimento de restauração passo a passo em `docs/backup.md`.

## O que foi descartado
- **Cópia sem criptografia "porque o repositório é privado"**: repositório e artefato mudam de visibilidade, e um descuido vira vazamento.
- **Guardar no Git**: dado de produção no histórico, para sempre.
- **Serviço de backup de terceiros**: custo e outro lugar com os dados.
- **Backup contínuo (arquivamento de WAL)**: RPO de minutos, mas exige um plano e infraestrutura que o custo zero não tem.

## Riscos conhecidos
- **RPO de 24 h**: um dia de dados pode se perder. Para um estúdio real que não aceita isso, o caminho é um plano pago com recuperação a um ponto no tempo.
- **A frase-senha é o ponto único de falha**: perdê-la inutiliza todos os backups. Está documentado guardá-la fora do GitHub.
- **Artefatos ficam na conta do GitHub**: se a conta cair, caem junto. Recomendado copiar um backup por mês para outro lugar.
- **Conta excluída (LGPD) depois do backup volta na restauração**: o procedimento manda reaplicar as exclusões pedidas desde a data do backup (ADR 0027).
- A prova de restauração de produção é `smoke` (a origem continua viva, então comparar contagens exatas seria enganoso); a igualdade exata é provada pelo ensaio da CI.

## Consequências
- O estúdio (ou o portfólio) tem um backup independente do provedor, criptografado, com a restauração provada a cada mudança.
- Um PR que quebre a restauração (por exemplo, uma migração que depende de algo que o `pg_restore` não recria) é barrado antes de entrar.
- Nenhum segredo é exigido para o projeto funcionar; ligar o backup real é um ato opcional e documentado do dono.
