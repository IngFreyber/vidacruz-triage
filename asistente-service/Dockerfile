# syntax=docker/dockerfile:1
# Construir desde la raíz del repositorio:  docker build -f asistente-service/Dockerfile -t vidacruz/asistente-service .

# --- 1) Modelo de embeddings ONNX dentro de la imagen ------------------------------------------------------
# En producción el contenedor no descarga nada al arrancar: no depende de la disponibilidad de Hugging Face,
# el arranque es predecible y la versión del modelo queda fijada en la imagen.
FROM alpine:3.20 AS modelo
ARG MODELO_BASE=https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main
RUN apk add --no-cache curl \
    && mkdir -p /modelo \
    && curl -fsSL --retry 3 -o /modelo/model.onnx "${MODELO_BASE}/onnx/model.onnx" \
    && curl -fsSL --retry 3 -o /modelo/tokenizer.json "${MODELO_BASE}/tokenizer.json"

# --- 2) Compilación (monorepo: se necesita el pom padre) ----------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY triage-service/pom.xml triage-service/
COPY asistente-service/pom.xml asistente-service/
COPY alertas-service/pom.xml alertas-service/
COPY asistente-service/src asistente-service/src
RUN --mount=type=cache,id=maven,target=/root/.m2,sharing=locked \
    mvn -B -q -pl asistente-service -am package -DskipTests
RUN java -Djarmode=tools -jar asistente-service/target/asistente-service.jar extract --layers --launcher --destination /capas

# --- 3) Ejecución --------------------------------------------------------------------------------------------
# Ubuntu (glibc) y no Alpine (musl): las bibliotecas nativas de ONNX Runtime requieren glibc.
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system vidacruz && useradd --system --gid vidacruz --home-dir /app vidacruz
WORKDIR /app
COPY --from=modelo --chown=vidacruz:vidacruz /modelo/ /opt/modelo/
COPY --from=build --chown=vidacruz:vidacruz /capas/dependencies/ ./
COPY --from=build --chown=vidacruz:vidacruz /capas/spring-boot-loader/ ./
COPY --from=build --chown=vidacruz:vidacruz /capas/snapshot-dependencies/ ./
COPY --from=build --chown=vidacruz:vidacruz /capas/application/ ./
USER vidacruz
EXPOSE 8082
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    EMBEDDINGS_MODEL_URI=file:/opt/modelo/model.onnx \
    EMBEDDINGS_TOKENIZER_URI=file:/opt/modelo/tokenizer.json \
    EMBEDDINGS_CACHE_DIR=/tmp/onnx
# Arranque más lento: indexa el corpus con el modelo ONNX antes de declararse listo.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=5 \
    CMD wget -qO- http://localhost:8082/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
