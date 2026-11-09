-- =====================================================================================
-- V1: esquema del servicio de triaje
--   pacientes          estado actual del agregado (con versión para concurrencia optimista)
--   auditoria_clinica  bitácora inmutable de cambios de estado/flag (RN-04)
--   outbox_eventos     transactional outbox hacia el topic triaje.eventos
-- =====================================================================================

CREATE TABLE pacientes (
    id                      UUID         PRIMARY KEY,
    nombre_completo         VARCHAR(100) NOT NULL,
    edad                    INTEGER      NOT NULL CHECK (edad BETWEEN 0 AND 120),
    frecuencia_cardiaca     INTEGER      NOT NULL CHECK (frecuencia_cardiaca BETWEEN 20 AND 250),
    presion_sistolica       INTEGER      NOT NULL CHECK (presion_sistolica BETWEEN 60 AND 260),
    presion_diastolica      INTEGER      NOT NULL CHECK (presion_diastolica BETWEEN 30 AND 160),
    spo2                    INTEGER      NOT NULL CHECK (spo2 BETWEEN 70 AND 100),
    temperatura             NUMERIC(3,1) NOT NULL CHECK (temperatura BETWEEN 34.0 AND 42.0),
    dolor                   INTEGER      NOT NULL CHECK (dolor BETWEEN 0 AND 10),
    gcs                     INTEGER      NOT NULL CHECK (gcs BETWEEN 3 AND 15),
    queja_principal         VARCHAR(500) NOT NULL,
    nivel                   VARCHAR(20)  NOT NULL
        CHECK (nivel IN ('CRITICO', 'URGENTE', 'URGENTE_2', 'NO_URGENTE', 'NO_URGENTE_2')),
    criterios_clasificacion TEXT[]       NOT NULL DEFAULT '{}',
    sla_segundos            INTEGER      NOT NULL CHECK (sla_segundos > 0),
    estado                  VARCHAR(20)  NOT NULL CHECK (estado IN ('REGISTRADO', 'EN_CONSULTA', 'ALTA')),
    escalado                BOOLEAN      NOT NULL DEFAULT FALSE,
    fecha_registro          TIMESTAMPTZ  NOT NULL,
    fecha_cambio_estado     TIMESTAMPTZ  NOT NULL,
    fecha_escalamiento      TIMESTAMPTZ,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_presion_diastolica_menor CHECK (presion_diastolica < presion_sistolica),
    CONSTRAINT ck_escalamiento_coherente CHECK (escalado = (fecha_escalamiento IS NOT NULL))
);

-- Candidatos a escalamiento (RF-03): índice parcial, solo pacientes en espera no escalados.
CREATE INDEX ix_pacientes_pendientes_sla ON pacientes (fecha_registro)
    WHERE estado = 'REGISTRADO' AND escalado = FALSE;

-- Reporte de operación por rango de fechas (RF-05).
CREATE INDEX ix_pacientes_fecha_registro ON pacientes (fecha_registro);


CREATE TABLE auditoria_clinica (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    evento_id       UUID         NOT NULL UNIQUE,
    paciente_id     UUID         NOT NULL REFERENCES pacientes (id),
    tipo_evento     VARCHAR(40)  NOT NULL
        CHECK (tipo_evento IN ('paciente_registrado', 'paciente_en_consulta', 'paciente_alta', 'paciente_escalado')),
    estado_anterior VARCHAR(20),
    estado_nuevo    VARCHAR(20)  NOT NULL,
    nivel           VARCHAR(20)  NOT NULL,
    detalle         TEXT,
    ocurrido_en     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_auditoria_paciente ON auditoria_clinica (paciente_id, ocurrido_en);
-- Primera entrada a consulta por paciente (RF-05).
CREATE INDEX ix_auditoria_tipo_paciente ON auditoria_clinica (tipo_evento, paciente_id, ocurrido_en);

-- RN-04: la auditoría es inmutable también a nivel de base de datos, no solo por ausencia de endpoint.
CREATE FUNCTION fn_auditoria_inmutable() RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'La auditoría clínica es inmutable: operación % no permitida', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_auditoria_sin_update_delete
    BEFORE UPDATE OR DELETE ON auditoria_clinica
    FOR EACH ROW EXECUTE FUNCTION fn_auditoria_inmutable();

CREATE TRIGGER trg_auditoria_sin_truncate
    BEFORE TRUNCATE ON auditoria_clinica
    FOR EACH STATEMENT EXECUTE FUNCTION fn_auditoria_inmutable();


CREATE TABLE outbox_eventos (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    evento_id     UUID        NOT NULL UNIQUE,
    agregado_id   UUID        NOT NULL,
    tipo_evento   VARCHAR(40) NOT NULL,
    payload       JSONB       NOT NULL,
    creado_en     TIMESTAMPTZ NOT NULL,
    publicado_en  TIMESTAMPTZ,
    intentos      INTEGER     NOT NULL DEFAULT 0
);

-- Pendientes de publicar, en orden de inserción.
CREATE INDEX ix_outbox_pendientes ON outbox_eventos (id) WHERE publicado_en IS NULL;
