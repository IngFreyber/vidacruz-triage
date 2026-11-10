# Entrega — Prueba técnica Dev. WebFlux (VidaCruz)

Plantilla para enviar la entrega al evaluador. **Las API keys NO van en este archivo**: se envían por un canal
privado (correo directo o gestor de secretos compartido) y se copian desde `infra/azure/.claves`, que no se versiona.

---

**Asunto:** Entrega prueba técnica Dev. WebFlux — Sistema de triaje VidaCruz

Hola,

Comparto la entrega de la prueba técnica:

1. **Repositorio:** https://github.com/IngFreyber/vidacruz-triage
   El historial tiene un commit por etapa, y cada uno es una versión funcionando.
2. **README:**
   - ejecución local con un solo comando (`docker compose up --build`);
   - diagrama de arquitectura;
   - decisiones clave y supuestos;
   - matriz de trazabilidad requerimiento → test.
3. **Tests:** `./mvnw verify` ejecuta 218 tests (unitarios + integración con Testcontainers), con BlockHound activo
   y una cobertura de líneas del 90–96 %.
4. **Aplicación en Azure (AKS):** http://57.162.225.46
   - Manifiestos: `k8s/`
   - Scripts de despliegue: `infra/azure/`
5. **ADR:** 9 decisiones documentadas en `docs/adr/`. RF-06 está en ADR-0007.

**Credenciales del entorno desplegado** (cabecera `X-API-Key`; para el stream SSE, parámetro `?api_key=`):

| Servicio | API key |
|---|---|
| triage | `<TRIAGE_API_KEY>` |
| asistente | `<ASISTENTE_API_KEY>` |
| alertas | `<ALERTAS_API_KEY>` |

Para probar rápido, la colección `docs/api/vidacruz.http` tiene todos los endpoints, los pacientes E1–E6 y las 3
consultas de ejemplo. Basta con cambiar las URL a `http://57.162.225.46` y pegar las claves.

Quedo atento a cualquier comentario.

---

## Lista de verificación antes de enviar

- [ ] Clúster encendido: `bash infra/azure/encender.sh`. Al terminar imprime la URL; confirmar que la IP no cambió
      (si cambió, actualizar el README y este mensaje).
- [ ] Prueba de humo contra la URL pública: un registro (201), un 401 sin clave y la consulta de ejemplo 1.
- [ ] Copiar las 3 API keys desde `infra/azure/.claves` al mensaje (canal privado).
- [ ] Repositorio público, o acceso concedido al evaluador.
- [ ] Después de la evaluación: `bash infra/azure/apagar.sh` (o `eliminar.sh`) para no consumir crédito.
