package com.vidacruz.asistente.application.service;

import com.vidacruz.asistente.application.port.out.BuscadorProtocolos;
import com.vidacruz.asistente.application.port.out.GeneradorRespuestas;
import com.vidacruz.asistente.domain.consulta.Fragmento;
import com.vidacruz.asistente.domain.consulta.PreguntaInvalidaException;
import com.vidacruz.asistente.domain.consulta.RespuestaClinica;
import com.vidacruz.asistente.domain.consulta.ValidadorCitas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * RF-04 parte A: consulta en lenguaje natural sobre protocolos clínicos con RAG y anti-alucinación (RN-05).
 *
 * <ol>
 *   <li><b>Recuperación con umbral</b>: si ninguna sección del corpus es suficientemente similar, se responde el
 *       aviso "no existe protocolo disponible" <b>sin invocar al LLM</b>: no hay forma de que genere contenido.</li>
 *   <li><b>Generación restringida</b>: el LLM recibe solo los fragmentos recuperados y debe citar.</li>
 *   <li><b>Validación de citas</b>: {@link ValidadorCitas} descarta cualquier respuesta con citas fuera de lo
 *       entregado o sin citas.</li>
 * </ol>
 */
public class ConsultaClinicaService {

    private static final Logger log = LoggerFactory.getLogger(ConsultaClinicaService.class);

    private final BuscadorProtocolos buscador;
    private final GeneradorRespuestas generador;
    private final ValidadorCitas validador;
    private final int minCaracteres;
    private final int maxCaracteres;

    public ConsultaClinicaService(BuscadorProtocolos buscador, GeneradorRespuestas generador, ValidadorCitas validador,
                                  int minCaracteres, int maxCaracteres) {
        this.buscador = buscador;
        this.generador = generador;
        this.validador = validador;
        this.minCaracteres = minCaracteres;
        this.maxCaracteres = maxCaracteres;
    }

    public Mono<RespuestaClinica> consultar(String pregunta) {
        return Mono.fromCallable(() -> validarPregunta(pregunta))
                .flatMap(p -> buscador.buscar(p)
                        .flatMap(fragmentos -> fragmentos.isEmpty()
                                ? sinProtocolo(p)
                                : generarYValidar(p, fragmentos)));
    }

    private Mono<RespuestaClinica> sinProtocolo(String pregunta) {
        log.info("Consulta sin protocolo relevante (no se invoca al LLM): '{}'", resumir(pregunta));
        return Mono.just(RespuestaClinica.sinProtocolo());
    }

    private Mono<RespuestaClinica> generarYValidar(String pregunta, List<Fragmento> fragmentos) {
        log.info("Consulta con {} fragmentos recuperados: {}", fragmentos.size(),
                fragmentos.stream().map(f -> f.protocolo() + "/" + f.seccion() + String.format("(%.2f)", f.similitud()))
                        .toList());
        return generador.generar(pregunta, fragmentos)
                .map(generada -> validador.validar(generada, fragmentos))
                .doOnNext(r -> {
                    switch (r.estado()) {
                        case RESPUESTA_DESCARTADA -> log.warn("RN-05: respuesta del LLM descartada por citas no "
                                + "verificables para '{}'", resumir(pregunta));
                        case SIN_PROTOCOLO -> log.info("El LLM declaró que los fragmentos no responden la pregunta");
                        case RESPONDIDA -> log.info("Respuesta clínica con fuentes {}", r.fuentes());
                    }
                });
    }

    private String validarPregunta(String pregunta) {
        String texto = pregunta == null ? "" : pregunta.strip();
        if (texto.length() < minCaracteres || texto.length() > maxCaracteres) {
            throw new PreguntaInvalidaException("La pregunta debe tener entre %d y %d caracteres; recibido %d"
                    .formatted(minCaracteres, maxCaracteres, texto.length()));
        }
        return texto;
    }

    private static String resumir(String texto) {
        return texto.length() <= 80 ? texto : texto.substring(0, 80) + "…";
    }
}
