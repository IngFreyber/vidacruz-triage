# syntax=docker/dockerfile:1
# Construir desde la raíz del repositorio:  docker build -f triage-service/Dockerfile -t vidacruz/triage-service .

# --- 1) Compilación (monorepo: se necesita el pom padre) ----------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY triage-service/pom.xml triage-service/
COPY asistente-service/pom.xml asistente-service/
COPY alertas-service/pom.xml alertas-service/
COPY triage-service/src triage-service/src
# Caché de dependencias compartida entre builds (no queda en la imagen).
RUN --mount=type=cache,id=maven,target=/root/.m2,sharing=locked \
    mvn -B -q -pl triage-service -am package -DskipTests
# Capas de Spring Boot: dependencias (cambian poco) separadas del código (cambia en cada build).
RUN java -Djarmode=tools -jar triage-service/target/triage-service.jar extract --layers --launcher --destination /capas

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
EXPOSE 8081
# La JVM toma el 75 % de la memoria del contenedor (no del host) y termina ante un OOM para que el orquestador
# reinicie el contenedor en lugar de dejarlo degradado.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
