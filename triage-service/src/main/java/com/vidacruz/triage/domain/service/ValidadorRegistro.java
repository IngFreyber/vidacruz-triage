package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException;
import com.vidacruz.triage.domain.exception.DatosPacienteInvalidosException.Violacion;
import com.vidacruz.triage.domain.model.SolicitudRegistro;

import java.util.ArrayList;
import java.util.List;

/**
 * Valida los rangos de datos de RF-01. Es la única fuente de verdad de los rangos: la capa web no los
 * duplica. Reporta <b>todas</b> las violaciones a la vez para que el cliente pueda corregirlas en un solo intento.
 */
public final class ValidadorRegistro {

    static final int NOMBRE_MIN = 3;
    static final int NOMBRE_MAX = 100;
    static final int QUEJA_MAX = 500;

    public void validar(SolicitudRegistro s) {
        List<Violacion> v = new ArrayList<>();

        texto(v, "nombreCompleto", s.nombreCompleto(), NOMBRE_MIN, NOMBRE_MAX);
        rango(v, "edad", s.edad(), 0, 120, "años");
        rango(v, "frecuenciaCardiaca", s.frecuenciaCardiaca(), 20, 250, "bpm");
        rango(v, "presionSistolica", s.presionSistolica(), 60, 260, "mmHg");
        rango(v, "presionDiastolica", s.presionDiastolica(), 30, 160, "mmHg");
        rango(v, "spo2", s.spo2(), 70, 100, "%");
        rangoDecimal(v, "temperatura", s.temperatura(), 34.0, 42.0, "°C");
        rango(v, "dolor", s.dolor(), 0, 10, "");
        rango(v, "gcs", s.gcs(), 3, 15, "");
        texto(v, "quejaPrincipal", s.quejaPrincipal(), 1, QUEJA_MAX);

        if (s.presionSistolica() != null && s.presionDiastolica() != null
                && s.presionDiastolica() >= s.presionSistolica()) {
            v.add(new Violacion("presionDiastolica",
                    "debe ser menor que la presión sistólica (%d mmHg); recibido %d mmHg"
                            .formatted(s.presionSistolica(), s.presionDiastolica())));
        }

        if (!v.isEmpty()) {
            throw new DatosPacienteInvalidosException(v);
        }
    }

    private static void rango(List<Violacion> v, String campo, Integer valor, int min, int max, String unidad) {
        if (valor == null) {
            v.add(new Violacion(campo, "es obligatorio"));
        } else if (valor < min || valor > max) {
            v.add(new Violacion(campo, "debe estar entre %d y %d %s; recibido %d"
                    .formatted(min, max, unidad, valor).replace("  ", " ")));
        }
    }

    private static void rangoDecimal(List<Violacion> v, String campo, Double valor, double min, double max,
                                     String unidad) {
        if (valor == null || valor.isNaN()) {
            v.add(new Violacion(campo, "es obligatorio"));
        } else if (valor < min || valor > max) {
            v.add(new Violacion(campo, "debe estar entre %s y %s %s; recibido %s"
                    .formatted(min, max, unidad, valor)));
        }
    }

    private static void texto(List<Violacion> v, String campo, String valor, int min, int max) {
        if (valor == null || valor.isBlank()) {
            v.add(new Violacion(campo, "es obligatorio"));
            return;
        }
        int largo = valor.strip().length();
        if (largo < min || largo > max) {
            v.add(new Violacion(campo, "debe tener entre %d y %d caracteres; recibido %d"
                    .formatted(min, max, largo)));
        }
    }
}
