package com.vidacruz.asistente.infrastructure.rag;

import com.vidacruz.asistente.application.port.out.BuscadorProtocolos;
import com.vidacruz.asistente.domain.consulta.Fragmento;
import com.vidacruz.asistente.infrastructure.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Recuperación semántica con umbral de similitud. El cálculo del embedding (ONNX, CPU) y la búsqueda son
 * síncronos: se ejecutan en {@code boundedElastic} para no bloquear el event loop.
 */
public class VectorStoreBuscadorProtocolos implements BuscadorProtocolos {

    private final VectorStore vectorStore;
    private final RagProperties props;

    public VectorStoreBuscadorProtocolos(VectorStore vectorStore, RagProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
    }

    @Override
    public Mono<List<Fragmento>> buscar(String pregunta) {
        return Mono.fromCallable(() -> vectorStore.similaritySearch(SearchRequest.builder()
                        .query(pregunta)
                        .topK(props.topK())
                        .similarityThreshold(props.umbralSimilitud())
                        .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(documentos -> documentos.stream().map(VectorStoreBuscadorProtocolos::fragmento).toList());
    }

    private static Fragmento fragmento(Document d) {
        return new Fragmento(
                (String) d.getMetadata().get(CorpusProtocolos.META_PROTOCOLO),
                (String) d.getMetadata().get(CorpusProtocolos.META_TITULO),
                (String) d.getMetadata().get(CorpusProtocolos.META_SECCION),
                (String) d.getMetadata().get(CorpusProtocolos.META_CONTENIDO),
                d.getScore() == null ? 0.0 : d.getScore());
    }
}
