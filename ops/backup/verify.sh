#!/usr/bin/env bash
# Confere se o banco RESTAURADO é igual ao de ORIGEM (ADR 0028): mesmas tabelas, mesma contagem de linhas em cada uma e a mesma impressão digital dos e-mails.
# Só faz sentido quando a origem não mudou desde o backup (o ensaio da CI); em produção, onde a origem continua viva, use o modo "smoke".
#
# Uso:   ./verify.sh compare ORIGEM_URL DESTINO_URL      # igualdade exata
#        ./verify.sh smoke DESTINO_URL                   # o destino tem as tabelas do app e o histórico de migrações sem falha
set -euo pipefail

mode="${1:?modo: compare ou smoke}"
q() { psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 "$1" -c "$2"; }

tables() {
  q "$1" "select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1"
}

counts() {
  local url="$1" t
  for t in $(tables "$url"); do
    echo "$t $(q "$url" "select count(*) from public.\"$t\"")"
  done
  echo "fingerprint $(q "$url" "select coalesce(md5(string_agg(lower(email), ',' order by lower(email))), 'vazio') from public.users")"
}

case "$mode" in
  compare)
    source_url="${2:?origem}"; target_url="${3:?destino}"
    a="$(counts "$source_url")"; b="$(counts "$target_url")"
    if [ "$a" != "$b" ]; then
      echo "O banco restaurado DIFERE da origem:" >&2
      diff <(echo "$a") <(echo "$b") >&2 || true
      exit 1
    fi
    echo "Restauração fiel: $(echo "$a" | grep -vc '^fingerprint') tabelas, mesmas contagens e mesma impressão digital."
    ;;
  smoke)
    target_url="${2:?destino}"
    n="$(tables "$target_url" | wc -l | tr -d ' ')"
    [ "$n" -ge 10 ] || { echo "Poucas tabelas no destino ($n): a restauração parece incompleta." >&2; exit 1; }
    for essential in users providers services appointments pets; do
      tables "$target_url" | grep -qx "$essential" || { echo "Falta a tabela essencial '$essential'." >&2; exit 1; }
    done
    if tables "$target_url" | grep -qx flyway_schema_history; then
      failed="$(q "$target_url" "select count(*) from public.flyway_schema_history where success = false")"
      [ "$failed" = "0" ] || { echo "O histórico de migrações tem $failed falha(s)." >&2; exit 1; }
    fi
    echo "Restauração íntegra: $n tabelas, tabelas essenciais presentes e migrações sem falha."
    ;;
  *) echo "modo desconhecido: $mode" >&2; exit 2 ;;
esac
