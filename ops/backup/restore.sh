#!/usr/bin/env bash
# Restaura um backup do QueueCare (ADR 0028) num banco de DESTINO. SOBRESCREVE o que houver no destino (--clean), por isso exige confirmação explícita.
#
# Uso:   TARGET_DATABASE_URL=postgresql://... BACKUP_PASSPHRASE=... CONFIRM_RESTORE=yes ./restore.sh arquivo.dump.gpg
#
# Ordem de segurança: (1) confere a soma de verificação, (2) decifra para uma pasta temporária (apagada ao sair), (3) lista o conteúdo do dump
# (um dump corrompido falha aqui, antes de tocar no destino) e só então (4) restaura, parando no primeiro erro.
set -euo pipefail

file="${1:?informe o arquivo de backup (.dump.gpg)}"
: "${TARGET_DATABASE_URL:?defina TARGET_DATABASE_URL (o banco que será SOBRESCRITO)}"
: "${BACKUP_PASSPHRASE:?defina BACKUP_PASSPHRASE}"
if [ "${CONFIRM_RESTORE:-}" != "yes" ]; then
  echo "Isto sobrescreve o banco de destino. Para continuar defina CONFIRM_RESTORE=yes." >&2
  exit 2
fi

if [ -f "$file.sha256" ]; then
  (cd "$(dirname "$file")" && sha256sum --check --quiet "$(basename "$file").sha256") || { echo "Soma de verificação NÃO confere: arquivo corrompido ou truncado." >&2; exit 3; }
else
  echo "Aviso: sem $file.sha256, a integridade do arquivo não foi conferida." >&2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
gpg --batch --yes --pinentry-mode loopback --passphrase-fd 3 --decrypt -o "$work/dump" "$file" 3<<<"$BACKUP_PASSPHRASE" 2>/dev/null \
  || { echo "Não foi possível decifrar: frase-senha errada ou arquivo corrompido." >&2; exit 4; }

pg_restore --list "$work/dump" > /dev/null || { echo "O dump está corrompido (pg_restore --list falhou); o destino não foi tocado." >&2; exit 5; }
pg_restore --dbname="$TARGET_DATABASE_URL" --no-owner --no-privileges --clean --if-exists --exit-on-error "$work/dump"
echo "Restauração concluída em um banco de destino."
