# Imagem do backend (ADR 0013). Duas etapas: compila com o JDK e roda só com o JRE, como usuário comum (sem root).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/queuecare-api-*.jar /app/app.jar

FROM eclipse-temurin:21-jre
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
