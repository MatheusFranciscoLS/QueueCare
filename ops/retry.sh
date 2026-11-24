#!/usr/bin/env bash
# Repete um comando que falha por causa da REDE (ADR 0013): o Maven Wrapper baixa o Maven no momento da compilação, com um único wget e sem nenhuma tentativa,
# então uma falha passageira do repositório central derrubava a CI e a publicação inteiras, sem relação nenhuma com o código.
#
# Uso:   ops/retry.sh [-n TENTATIVAS] [-w SEGUNDOS] -- comando [argumentos...]
#        -n  quantas vezes tentar no total (padrão 4)
#        -w  espera ANTES da 2ª tentativa; a cada nova tentativa a espera soma de novo esse valor: w, 2w, 3w... (padrão 10)
#
# Só repete o que está depois do "--". Uma falha REAL (teste quebrado, erro de compilação) também é repetida até acabar as tentativas: por isso use este script só
# em passos que baixam coisas (dependency:go-offline), nunca em "verify".
# Sai com o código do ÚLTIMO comando se todas as tentativas falharem.
set -uo pipefail

tentativas=4
espera=10
while getopts ":n:w:" opcao; do
  case "$opcao" in
    n) tentativas="$OPTARG" ;;
    w) espera="$OPTARG" ;;
    *) echo "uso: retry.sh [-n tentativas] [-w segundos] -- comando [args...]" >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
[ "${1:-}" = "--" ] && shift
[ "$#" -gt 0 ] || { echo "retry.sh: informe o comando depois de --" >&2; exit 2; }
case "$tentativas$espera" in *[!0-9]*) echo "retry.sh: -n e -w precisam ser números inteiros" >&2; exit 2 ;; esac
[ "$tentativas" -ge 1 ] || { echo "retry.sh: -n precisa ser pelo menos 1" >&2; exit 2; }

codigo=0
for ((i = 1; i <= tentativas; i++)); do
  "$@" && exit 0
  codigo=$?
  if [ "$i" -lt "$tentativas" ]; then
    pausa=$((espera * i))
    echo "retry.sh: tentativa $i de $tentativas falhou (código $codigo); nova tentativa em ${pausa}s..." >&2
    sleep "$pausa"
  fi
done
echo "retry.sh: as $tentativas tentativas falharam (último código $codigo)." >&2
exit "$codigo"
