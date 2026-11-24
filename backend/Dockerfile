# Imagem do backend (ADR 0013). Duas etapas: compila com o JDK e roda só com o JRE, como usuário comum (sem root).
# Imagens base FIXADAS por digest (reprodutível). Para receber correções de segurança: docker pull eclipse-temurin:21-jdk (e 21-jre), copie o novo
# Digest (docker image inspect --format '{{index .RepoDigests 0}}') para as duas linhas FROM, rode o CI e revise o PR.
FROM eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07 AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
# O Maven Wrapper baixa o Maven AQUI, com um wget sem nenhuma tentativa: uma falha passageira da rede derrubava a publicação inteira, sem relação com o código.
# Até 4 tentativas com espera crescente (10 s, 20 s, 30 s): a mesma lógica do ops/retry.sh, que não está no contexto desta imagem.
RUN chmod +x mvnw && \
    for i in 1 2 3 4; do \
      ./mvnw -B -q dependency:go-offline && exit 0; \
      echo "Falha ao baixar o Maven ou as dependencias (tentativa $i de 4); nova tentativa em $((i * 10))s..." >&2; \
      sleep $((i * 10)); \
    done; \
    exit 1
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/queuecare-api-*.jar /app/app.jar

FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
RUN useradd --system --create-home --uid 10001 queuecare
USER queuecare
WORKDIR /home/queuecare
COPY --from=build /app/app.jar app.jar
# Perfil de produção (application-prod.yml). A memória é uma HIPÓTESE inicial para 512 MB (ADR 0013): medir antes de publicar.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -Xss512k"
EXPOSE 8080
# A plataforma informa a porta em PORT (o perfil prod a lê). Segredos vêm SÓ do ambiente, nunca da imagem.
ENTRYPOINT ["java", "-jar", "app.jar"]
