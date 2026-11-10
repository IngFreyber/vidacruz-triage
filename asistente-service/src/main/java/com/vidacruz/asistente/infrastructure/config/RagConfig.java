package com.vidacruz.asistente.infrastructure.config;

import com.vidacruz.asistente.application.port.out.BuscadorProtocolos;
import com.vidacruz.asistente.application.port.out.GeneradorRespuestas;
import com.vidacruz.asistente.application.service.ConsultaClinicaService;
import com.vidacruz.asistente.domain.consulta.ValidadorCitas;
import com.vidacruz.asistente.infrastructure.rag.CorpusProtocolos;
import com.vidacruz.asistente.infrastructure.rag.LlmGeneradorRespuestas;
import com.vidacruz.asistente.infrastructure.rag.VectorStoreBuscadorProtocolos;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Ensambla el RAG. El corpus se vectoriza al arrancar, <b>antes</b> de que el servicio quede listo
 * (readiness): nunca se atiende una consulta con el índice incompleto.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);
    static final String INSTANCIA_LLM = "llm";

    @Bean
    VectorStore vectorStoreProtocolos(EmbeddingModel embeddingModel, RagProperties props) {
        List<Document> secciones = CorpusProtocolos.cargar(props.corpus());
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(secciones);
        log.info("Corpus de protocolos indexado: {} secciones de {} protocolos", secciones.size(),
                secciones.stream().map(d -> d.getMetadata().get(CorpusProtocolos.META_PROTOCOLO)).distinct().count());
        return store;
    }

    @Bean
    BuscadorProtocolos buscadorProtocolos(VectorStore vectorStoreProtocolos, RagProperties props) {
        return new VectorStoreBuscadorProtocolos(vectorStoreProtocolos, props);
    }

    @Bean
    GeneradorRespuestas generadorRespuestas(ChatModel chatModel, CircuitBreakerRegistry circuitBreakers,
                                            TimeLimiterRegistry timeLimiters, MeterRegistry metricas,
                                            @Value("${spring.ai.openai.chat.options.model:desconocido}") String modelo) {
        return new LlmGeneradorRespuestas(chatModel, circuitBreakers.circuitBreaker(INSTANCIA_LLM),
                timeLimiters.timeLimiter(INSTANCIA_LLM), metricas, modelo);
    }

    @Bean
    ConsultaClinicaService consultaClinicaService(BuscadorProtocolos buscador, GeneradorRespuestas generador,
                                                  RagProperties props) {
        return new ConsultaClinicaService(buscador, generador, new ValidadorCitas(), props.minCaracteres(),
                props.maxCaracteres());
    }
}
