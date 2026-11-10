-- Marca una alerta como atendida si está pendiente. Atómico: ante dos confirmaciones simultáneas, gana la
-- primera y se conserva quién la tomó.
--
-- KEYS[1] alertas:alerta:{id} · KEYS[2] alertas:pendientes
-- ARGV[1] id · ARGV[2] atendidaPor · ARGV[3] atendidaEn (ISO 8601)
--
-- Retorna 1 si esta llamada la marcó, 0 si ya estaba atendida, -1 si no existe.

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
if redis.call('HGET', KEYS[1], 'estado') == 'ATENDIDA' then
    return 0
end
redis.call('HSET', KEYS[1], 'estado', 'ATENDIDA', 'atendidaPor', ARGV[2], 'atendidaEn', ARGV[3])
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
