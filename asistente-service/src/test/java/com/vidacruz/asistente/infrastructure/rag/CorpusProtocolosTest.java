package com.vidacruz.asistente.infrastructure.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class CorpusProtocolosTest {

    @Test
    void cargaElCorpusOficialPorSeccion() {
        List<Document> secciones = CorpusProtocolos.cargar("classpath:protocolos/*.md");

        assertThat(secciones).hasSize(7);
        assertThat(secciones).extracting(d -> d.getMetadata().get(CorpusProtocolos.META_PROTOCOLO) + "/"
                        + d.getMetadata().get(CorpusProtocolos.META_SECCION))
                .containsExactly(
                        "PROTO-001/Definición", "PROTO-001/Medidas iniciales", "PROTO-001/Contraindicaciones",
                        "PROTO-002/Criterios", "PROTO-002/Acciones iniciales",
                        "PROTO-003/Clasificación", "PROTO-003/Rehidratación");
    }

    @Test
    void elContenidoEsLiteralYLasPalabrasClaveSoloEnriquecenElTextoVectorizado() {
        Document medidas = CorpusProtocolos.cargar("classpath:protocolos/*.md").get(1);

        assertThat(medidas.getMetadata().get(CorpusProtocolos.META_CONTENIDO)).isEqualTo(
                "Reconfirmar la presión tras 5 minutos de reposo. No reducir la presión más de un 25% en la "
                        + "primera hora. Vigilar signos de encefalopatía.");
        assertThat((String) medidas.getMetadata().get(CorpusProtocolos.META_CONTENIDO))
                .doesNotContain("hipertensión arterial severa");
        assertThat(medidas.getText()).contains("hipertensión arterial severa").contains("Medidas iniciales");
    }

    @Test
    void rechazaProtocolosSinCabecera() {
        assertThatIllegalStateException()
                .isThrownBy(() -> CorpusProtocolos.parsear("## Sección\ntexto", "malo.md"))
                .withMessageContaining("cabecera");
    }
}
