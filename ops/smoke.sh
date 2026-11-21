#!/usr/bin/env bash
# Verificação rápida pós-deploy do QueueCare (ADR 0029). Serve para rodar na mão depois de publicar e como base de um monitor externo.
#
# Uso:  ./ops/smoke.sh https://api-do-queuecare.onrender.com [https://site-na-vercel.app]
#       (o primeiro argumento é a API; o segundo, opcional, é o site)
#
# Confere: a API está viva, consegue falar com o banco, devolve o código de requisição e responde 401 (e não 500) numa rota protegida sem login.
# Sai com código diferente de zero no primeiro problema, dizendo o que falhou. Não grava nada e não usa credenciais.
set -euo pipefail

api="${1:?informe a URL da API (ex.: https://queuecare-api.onrender.com)}"
site="${2:-}"
api="${api%/}"
fail() { echo "FALHOU: $*" >&2; exit 1; }
# O Render gratuito dorme depois de um tempo sem uso e leva cerca de um minuto para acordar: a primeira chamada espera mais.
# Nunca aborta sozinho: se o curl falhar (sem conexão, tempo esgotado) o código sai como 000 e o passo mostra a própria mensagem.
get() { curl --silent --location --max-time 120 --retry 2 --retry-delay 5 "$@" || true; }

echo "1/5 API viva (liveness)…"
code="$(get --output /dev/null --write-out '%{http_code}' "$api/actuator/health/liveness")"
[ "$code" = "200" ] || fail "liveness respondeu $code (esperado 200). A API pode estar acordando: tente de novo em 1 minuto."

echo "2/5 API fala com o banco (readiness)…"
code="$(get --output /dev/null --write-out '%{http_code}' "$api/actuator/health/readiness")"
[ "$code" = "200" ] || fail "readiness respondeu $code (esperado 200). O banco pode estar dormindo ou fora do ar (Neon): veja docs/operacao.md."

echo "3/5 Código de requisição…"
rid="$(get --silent --head "$api/actuator/health/liveness" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-request-id"{print $2}')"
[ -n "$rid" ] || fail "a resposta não trouxe X-Request-Id (o filtro do ADR 0029 não está ativo nesta versão)."

echo "4/5 Rota protegida sem login responde 401…"
code="$(get --output /dev/null --write-out '%{http_code}' "$api/api/me")"
[ "$code" = "401" ] || fail "/api/me sem token respondeu $code (esperado 401)."

if [ -n "$site" ]; then
  echo "5/5 Site no ar…"
  site="${site%/}"
  code="$(get --output /dev/null --write-out '%{http_code}' "$site/")"
  [ "$code" = "200" ] || fail "o site respondeu $code (esperado 200; 000 = não conectou)."
else
  echo "5/5 Site: pulado (informe a URL como segundo argumento)."
fi

echo "OK: tudo respondeu como esperado (último código de requisição: $rid)."
