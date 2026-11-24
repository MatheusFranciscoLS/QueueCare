#!/usr/bin/env bash
# Testes do ops/retry.sh (ADR 0013). Rodam na CI em segundos (espera zero) e na máquina de quem mexer no script: bash ops/retry.test.sh
set -uo pipefail

aqui="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
retry="$aqui/retry.sh"
pasta="$(mktemp -d)"
trap 'rm -rf "$pasta"' EXIT
falhas=0

confere() { # confere "descrição" "esperado" "obtido"
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FALHOU $1: esperado [$2], obtido [$3]"; falhas=$((falhas + 1)); fi
}

# Um comando que falha as N primeiras vezes e depois passa (conta as chamadas num arquivo).
cat > "$pasta/instavel.sh" <<'SCRIPT'
#!/usr/bin/env bash
arquivo="$1"; falhar_ate="$2"
n=$(( $(cat "$arquivo" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$arquivo"
[ "$n" -gt "$falhar_ate" ]
SCRIPT

rm -f "$pasta/c1"; bash "$retry" -n 4 -w 0 -- bash "$pasta/instavel.sh" "$pasta/c1" 0 2>/dev/null
confere "passa de primeira: sai com 0 e chama 1 vez" "0 1" "$? $(cat "$pasta/c1")"

rm -f "$pasta/c2"; bash "$retry" -n 4 -w 0 -- bash "$pasta/instavel.sh" "$pasta/c2" 2 2>/dev/null
confere "falha 2 vezes e passa na 3ª: sai com 0 e chama 3 vezes" "0 3" "$? $(cat "$pasta/c2")"

rm -f "$pasta/c3"; bash "$retry" -n 3 -w 0 -- bash "$pasta/instavel.sh" "$pasta/c3" 99 2>/dev/null
codigo=$?
confere "falha sempre: para no limite (3 chamadas) e sai com erro" "1 3" "$codigo $(cat "$pasta/c3")"

bash "$retry" -n 2 -w 0 -- bash -c 'exit 7' 2>/dev/null
confere "devolve o código do ÚLTIMO comando" "7" "$?"

saida="$(bash "$retry" -n 3 -w 0 -- bash "$pasta/instavel.sh" "$pasta/c4" 99 2>&1 >/dev/null)"
confere "avisa cada espera e o fim, sem esconder a falha" "2 1" "$(echo "$saida" | grep -c 'nova tentativa') $(echo "$saida" | grep -c 'tentativas falharam')"

bash "$retry" -n 2 -w 0 -- bash -c '[ "$1" = "dois espacos  aqui" ]' _ "dois espacos  aqui" 2>/dev/null
confere "preserva argumentos com espaços" "0" "$?"

bash "$retry" -n 2 -w 0 2>/dev/null
confere "sem comando: erro de uso (2)" "2" "$?"
bash "$retry" -n abc -w 0 -- true 2>/dev/null
confere "número inválido: erro de uso (2)" "2" "$?"
bash "$retry" -n 0 -w 0 -- true 2>/dev/null
confere "zero tentativas: erro de uso (2)" "2" "$?"

# A espera cresce de verdade (w, 2w): com -w 1 e 3 tentativas que falham, o total passa de 2 s (1 + 2 = 3 s).
inicio=$(date +%s); bash "$retry" -n 3 -w 1 -- false 2>/dev/null; fim=$(date +%s)
confere "a espera cresce (1s + 2s)" "ok" "$([ $((fim - inicio)) -ge 3 ] && echo ok || echo "só $((fim - inicio))s")"

if [ "$falhas" -gt 0 ]; then echo "$falhas teste(s) falharam."; exit 1; fi
echo "Todos os testes do retry.sh passaram."
