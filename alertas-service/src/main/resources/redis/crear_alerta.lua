-- Crea una alerta solo si no existe (idempotencia por eventId) y la indexa. Atómico.
--
-- KEYS[1] alertas:alerta:{id}   hash con los datos de la alerta
-- KEYS[2] alertas:indice        zset id -> escaladoEn (ms), todas las alertas
-- KEYS[3] alertas:pendientes    zset id -> escaladoEn (ms), solo pendientes
-- ARGV[1] id · ARGV[2] escaladoEn (ms) · ARGV[3] TTL del hash (s) · ARGV[4] corte de retención (ms)
-- ARGV[5..] pares campo/valor del hash
--
-- Retorna 1 si se creó, 0 si ya existía (reentrega del evento).

if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

local campos = {}
for i = 5, #ARGV do
    campos[#campos + 1] = ARGV[i]
end
redis.call('HSET', KEYS[1], unpack(campos))
redis.call('EXPIRE', KEYS[1], ARGV[3])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
redis.call('ZADD', KEYS[3], ARGV[2], ARGV[1])

-- Retención: los índices no crecen indefinidamente (los hashes expiran solos por TTL).
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[4])
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[4])
return 1
