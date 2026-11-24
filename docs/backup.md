# Backup e restauração do banco

Estratégia do ADR 0028. Os scripts ficam em `ops/backup/` e são **os mesmos** no ensaio automático da CI e no backup agendado.

## O que existe

| O quê | Onde | Quando roda |
|---|---|---|
| `backup.sh` | `ops/backup/` | `pg_dump` em formato custom, **criptografado** (AES-256) e com soma de verificação |
| `restore.sh` | `ops/backup/` | Confere a soma, decifra para uma pasta temporária, **lista o dump antes de tocar no destino** e restaura parando no primeiro erro. Exige `CONFIRM_RESTORE=yes` |
| `verify.sh` | `ops/backup/` | `compare`: origem e destino idênticos (tabelas, contagens, impressão digital dos e-mails). `smoke`: tabelas essenciais presentes e migrações sem falha |
| Job **Backup (ensaio de restauração)** | `.github/workflows/ci.yml` | **A cada PR e push.** Aplica todas as migrações num PostgreSQL, planta dados fictícios, faz o backup, restaura num segundo PostgreSQL e exige que seja idêntico. Prova também as recusas (sem confirmação, frase errada, arquivo corrompido) e que não dá para gerar backup sem criptografia |
| Workflow **Backup do banco (sob demanda)** | `.github/workflows/backup.yml` | Só sob demanda (o agendamento diário foi removido: sem segredos cadastrados ele era ruído; a restauração continua ensaiada em cada PR pelo ci.yml). Backup do banco de produção, **prova de restauração num banco descartável** e guarda o arquivo criptografado por 14 dias |

O backup diário **só roda se o dono cadastrar os segredos**; sem eles o job termina em sucesso, sem fazer nada. O projeto é de portfólio e não exige nenhum segredo.

## Como ligar o backup de produção (opcional)

No GitHub: *Settings → Secrets and variables → Actions → New repository secret*.

| Segredo | Valor |
|---|---|
| `BACKUP_DATABASE_URL` | URL **direta** do Neon (sem `-pooler`) no formato `postgresql://usuario:senha@host/banco?sslmode=require`. Um usuário só de leitura é o ideal |
| `BACKUP_PASSPHRASE` | Frase-senha de **16 caracteres ou mais**. Guarde uma cópia FORA do GitHub (gerenciador de senhas): sem ela, o backup não abre |

Rode uma vez em *Actions → Backup diário → Run workflow* e confira que o job terminou verde e que há um artefato `backup-<id>`.

> O artefato de um repositório **público** pode ser baixado por qualquer conta do GitHub. Por isso o backup NUNCA é gravado sem criptografia (o script recusa) e a frase-senha é o que protege os dados.

## Metas (RPO e RTO) — o que prometer e o que não

- **RPO (quanto dado se pode perder): até 24 horas.** O backup é diário; tudo o que aconteceu depois do último backup se perde se o banco for destruído.
- **RTO (quanto tempo para voltar): cerca de 1 hora para um banco deste porte**, somando criar o banco novo, rodar a restauração e apontar a API (`DB_URL`) para ele. O ensaio da CI mede a restauração de um banco pequeno em segundos; o tempo real cresce com o volume.
- São metas do **projeto**, não garantias da hospedagem gratuita. Se o estúdio não aceita perder um dia, o backup diário não basta: seria preciso um plano pago com recuperação para um ponto no tempo.
- O **Neon** tem recursos próprios de restauração (histórico do banco por um período curto, que depende do plano). Eles ajudam, mas ficam no mesmo provedor: o backup do repositório é a cópia **independente**. Confira os limites atuais na página do Neon, porque mudam.

## Restaurar de verdade (passo a passo)

1. **Baixe o artefato** em *Actions → Backup diário → (execução) → Artifacts*. Descompacte: você terá `queuecare-<data>.dump.gpg` e `.sha256`.
2. **Crie um banco novo e vazio** no Neon (nunca restaure por cima do que está em uso sem ter certeza). Pegue a URL direta dele.
3. **Restaure** (precisa de `postgresql-client` e `gnupg`):
   ```bash
   TARGET_DATABASE_URL='postgresql://usuario:senha@host/banco_novo?sslmode=require' \
   BACKUP_PASSPHRASE='a-frase-senha' CONFIRM_RESTORE=yes \
   bash ops/backup/restore.sh queuecare-AAAAMMDDTHHMMSSZ.dump.gpg
   ```
4. **Confira:** `bash ops/backup/verify.sh smoke 'postgresql://...banco_novo...'`.
5. **Aponte a API** para o banco novo (`DB_URL` e `DB_DIRECT_URL` no Render) e reinicie. Confira `/actuator/health/readiness` e entre no app.
6. **Reaplique as exclusões de conta** pedidas depois da data do backup (ver `docs/privacidade-dados.md`): uma conta excluída depois do backup volta com seus dados na restauração.

## Ensaio periódico

A CI já prova que o par `backup.sh`/`restore.sh` funciona a cada mudança. O que ela **não** prova é que o backup **do seu banco real** abre: por isso o workflow diário restaura cada backup num banco descartável e roda `verify.sh smoke`. Se um dia ele ficar vermelho, o backup daquele dia não vale: investigue antes do próximo.

Uma vez por trimestre, faça o passo a passo acima de ponta a ponta num banco novo e anote quanto tempo levou (RTO real).

## Versão do PostgreSQL (importante)

Os workflows usam o **cliente 16** (`postgresql-client-16`) porque o banco é PostgreSQL 16 (Neon, ver `docs/publicacao.md`). Um `pg_dump` **mais novo** que o servidor gera comandos que ele não entende (o ensaio da CI pegou isso na primeira execução: o dump do cliente 17 traz `SET transaction_timeout`, que o 16 recusa). Regras:

- o cliente do backup deve ter a **mesma versão principal do servidor de origem** (ou maior, nunca menor);
- o banco de **destino** da restauração deve ser da **mesma versão ou mais novo** que a do cliente que fez o dump;
- se o Neon for atualizado para o 17, troque `postgresql-client-16` por `-17` nos dois workflows e a imagem `postgres:16` por `postgres:17` (o ensaio da CI avisa se algo não casar).

## O que este backup não cobre

- Alterações depois do último backup (RPO de 24 h).
- Segredos e configuração (`JWT_SECRET`, variáveis do Render): estão fora do banco; guarde-os num gerenciador de senhas.
- A conta do GitHub: se ela cair, os artefatos caem junto. Copiar um backup por mês para outro lugar é barato e vale a pena.
