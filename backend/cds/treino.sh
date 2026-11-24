#!/bin/sh
# Treino do AppCDS (ADR 0040): roda a API UMA vez, durante o `docker build`, só até o contexto Spring ficar pronto (sem abrir porta e sem banco de dados), e deixa o JVM gravar
# em app/app.jsa as classes que carregou. Na partida de verdade o JVM mapeia esse arquivo em vez de carregar e verificar milhares de classes de novo, o que, numa CPU fraca
# (Render gratuito), encurta a partida em dezenas de segundos.
#
# Os valores abaixo são de MENTIRA e só existem durante este comando (nada é gravado na imagem): servem para a configuração de produção carregar sem banco.
# Sem Flyway, sem validação do esquema e sem consulta de metadados: o Hibernate não tenta abrir conexão (o dialeto vem explícito), e o pool não falha por não achar o banco.
#
# Uso: treino.sh criar   (no build: grava app/app.jsa)
#      treino.sh usar    (na CI: sobe a imagem USANDO o arquivo e falha se o JVM o recusar, -Xshare:on)
#      treino.sh sem     (na CI: o mesmo sem o arquivo, só para comparar o tempo nos logs)
set -eu

MODO="${1:-criar}"
case "$MODO" in
  criar) CDS="-XX:ArchiveClassesAtExit=app/app.jsa" ;;
  usar) CDS="-XX:SharedArchiveFile=app/app.jsa -Xshare:on" ;;
  sem) CDS="-Xshare:auto" ;;
  *) echo "uso: treino.sh [criar|usar|sem]" >&2; exit 2 ;;
esac

JWT_FALSO="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"

# $CDS sem aspas de propósito: são dois argumentos no modo "usar".
exec env \
  SPRING_PROFILES_ACTIVE=prod \
  DB_URL=jdbc:postgresql://sem-banco.invalid:5432/treino \
  DB_DIRECT_URL=jdbc:postgresql://sem-banco.invalid:5432/treino \
  DB_USER=treino \
  CORS_ALLOWED_ORIGINS=https://treino.invalid \
  JWT_SECRET="$JWT_FALSO" \
  java $CDS \
  -Dspring.context.exit=onRefresh \
  -Dspring.flyway.enabled=false \
  -Dspring.jpa.hibernate.ddl-auto=none \
  -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
  -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
  -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
  -jar app/app.jar
