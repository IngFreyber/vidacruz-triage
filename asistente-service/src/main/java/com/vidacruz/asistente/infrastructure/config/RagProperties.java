package com.vidacruz.asistente.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del RAG (RF-04 parte A).
 *
 * @param corpus          patrón de los archivos del corpus oficial
 * @param umbralSimilitud similitud mínima (coseno, 0..1) para considerar relevante un fragmento; por debajo, la
 *                        respuesta es "no existe protocolo disponible" sin invocar al LLM. Calibrado en ADR-0005
 * @param topK            máximo de fragmentos entregados al LLM
 * @param minCaracteres   largo mínimo de la pregunta
 * @param maxCaracteres   largo máximo de la pregunta (acota costo y superficie de prompt injection)
 */
@ConfigurationProperties(prefix = "asistente.rag")
public record RagProperties(
        @DefaultValue("classpath:protocolos/*.md") String corpus,
        @DefaultValue("0.42") double umbralSimilitud,
        @DefaultValue("4") int topK,
        @DefaultValue("5") int minCaracteres,
        @DefaultValue("1000") int maxCaracteres) {
}
