# Imagem do backend (ADR 0013). Duas etapas: compila com o JDK e roda só com o JRE, como usuário comum (sem root).
# Imagens base FIXADAS por digest (reprodutível). Para receber correções de segurança: docker pull eclipse-temurin:21-jdk (e 21-jre), copie o novo
# Digest (docker image inspect --format '{{index .RepoDigests 0}}') para as duas linhas FROM, rode o CI e revise o PR.
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
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

FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d
RUN useradd --system --create-home --uid 10001 queuecare
USER queuecare
WORKDIR /home/queuecare
COPY --from=build /app/app.jar app.jar
# Perfil de produção (application-prod.yml). A memória é uma HIPÓTESE inicial para 512 MB (ADR 0013): medir antes de publicar.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -Xss512k"
# AppCDS (ADR 0040): o JAR é extraído (o JVM só mapeia arquivo de classes de JARs soltos) e a API roda UMA vez, aqui no build, até o contexto Spring ficar pronto (sem banco),
# para o JVM gravar em app/app.jsa as classes carregadas. Na partida de verdade ele mapeia o arquivo em vez de carregar e verificar tudo de novo: no Render gratuito (CPU fraca)
# isso encurta dezenas de segundos. Se o treino falhar, o BUILD falha (a versão atual da API continua no ar); se o JVM recusar o arquivo na partida, ele segue sem ele.
RUN java -Djarmode=tools -jar app.jar extract --destination app && rm app.jar
COPY cds/treino.sh treino.sh
RUN sh treino.sh criar
EXPOSE 8080
# A plataforma informa a porta em PORT (o perfil prod a lê). Segredos vêm SÓ do ambiente, nunca da imagem.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/home/queuecare/app/app.jsa", "-jar", "app/app.jar"]
