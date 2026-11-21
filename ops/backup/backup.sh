#!/usr/bin/env bash
# Backup do banco do QueueCare (ADR 0028): pg_dump em formato custom, CRIPTOGRAFADO com senha (AES-256) e com soma de verificação.
#
# Uso:   DATABASE_URL=postgresql://usuario:senha@host/banco?sslmode=require BACKUP_PASSPHRASE=... ./backup.sh [pasta-de-saida]
# Saída: <pasta>/queuecare-AAAAMMDDTHHMMSSZ.dump.gpg  e  <arquivo>.sha256
#
# Regras que não se negociam:
#   - NUNCA gera cópia sem criptografia (o artefato de um repositório público pode ser baixado por qualquer conta do GitHub);
#   - não imprime a URL, a senha do banco nem a frase-senha;
#   - a frase-senha entra pelo descritor 3, não por argumento (argumento aparece na lista de processos).
set -euo pipefail

: "${DATABASE_URL:?defina DATABASE_URL (postgresql://usuario:senha@host/banco?sslmode=require)}"
: "${BACKUP_PASSPHRASE:?defina BACKUP_PASSPHRASE: o backup nunca é gravado sem criptografia}"
if [ "${#BACKUP_PASSPHRASE}" -lt 16 ]; then
  echo "BACKUP_PASSPHRASE curta demais: use ao menos 16 caracteres." >&2
  exit 2
fi

out_dir="${1:-./backups}"
mkdir -p "$out_dir"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$out_dir/queuecare-$stamp.dump.gpg"

# O dump já vem comprimido (formato custom); o gpg só criptografa. Se qualquer etapa falhar o pipefail derruba o script e o arquivo parcial é apagado.
trap 'rm -f "$file"' ERR
pg_dump --format=custom --no-owner --no-privileges "$DATABASE_URL" \
  | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 3 --symmetric --cipher-algo AES256 --compress-algo none -o "$file" 3<<<"$BACKUP_PASSPHRASE"
trap - ERR

# Soma de verificação do arquivo CRIPTOGRAFADO: detecta download corrompido ou arquivo truncado antes de tentar restaurar.
(cd "$out_dir" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")

bytes="$(wc -c < "$file" | tr -d ' ')"
if [ "$bytes" -lt 1024 ]; then
  echo "Backup suspeito: só $bytes bytes." >&2
  rm -f "$file" "$file.sha256"
  exit 3
fi
echo "Backup criado: $file ($bytes bytes)"
