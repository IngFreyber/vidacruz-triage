# syntax=docker/dockerfile:1
# Construir desde la raíz del repositorio:  docker build -f alertas-service/Dockerfile -t vidacruz/alertas-service .

# --- 1) Compilación (monorepo: se necesita el pom padre) ----------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY triage-service/pom.xml triage-service/
COPY asistente-service/pom.xml asistente-service/
COPY alertas-service/pom.xml alertas-service/
COPY alertas-service/src alertas-service/src
RUN --mount=type=cache,id=maven,target=/root/.m2,sharing=locked \
    mvn -B -q -pl alertas-service -am package -DskipTests
RUN java -Djarmode=tools -jar alertas-service/target/alertas-service.jar extract --layers --launcher --destination /capas

# --- 2) Ejecución: JRE mínima, usuario sin privilegios ----------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
# UID/GID numéricos fijos: Kubernetes (runAsNonRoot) solo puede verificar un usuario no root si es numérico.
RUN addgroup -S -g 10001 vidacruz && adduser -S -u 10001 -G vidacruz vidacruz
WORKDIR /app
COPY --from=build --chown=vidacruz:vidacruz /capas/dependencies/ ./
COPY --from=build --chown=vidacruz:vidacruz /capas/spring-boot-loader/ ./
COPY --from=build --chown=vidacruz:vidacruz /capas/snapshot-dependencies/ ./
COPY --from=build --chown=vidacruz:vidacruz /capas/application/ ./
USER 10001:10001
EXPOSE 8083
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8083/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
