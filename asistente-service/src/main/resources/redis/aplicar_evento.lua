-- Aplica un evento de triaje a la vista de urgencias de forma ATÓMICA (Redis ejecuta el script sin
-- intercalar otros comandos). Es idempotente: reentregas, reprocesos y eventos tardíos no alteran los
-- contadores.
--
-- KEYS[1] urgencias:evento:{eventId}    marca de deduplicación (con TTL)
-- KEYS[2] urgencias:paciente:{id}       hash con el estado del paciente: nivel, estado, ts
-- KEYS[3] urgencias:activos             hash nivel -> pacientes activos
-- KEYS[4] urgencias:actualizado-en      epoch ms del último evento aplicado
--
-- ARGV[1] TTL (segundos) de la marca de deduplicación
-- ARGV[2] nivel del paciente según el evento
-- ARGV[3] estado nuevo (REGISTRADO | EN_CONSULTA | ALTA)
-- ARGV[4] instante del evento (epoch ms)
-- ARGV[5] TTL (segundos) del hash de un paciente dado de alta
--
-- Retorna: 1 aplicado · 0 duplicado · 2 paciente ya de alta · 3 evento obsoleto

-- 1) Deduplicación por eventId.
if not redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
    return 0
end

local estadoPrevio = redis.call('HGET', KEYS[2], 'estado')
local nivelPrevio = redis.call('HGET', KEYS[2], 'nivel')
local tsPrevio = tonumber(redis.call('HGET', KEYS[2], 'ts') or '-1')
local ts = tonumber(ARGV[4])

-- 2) ALTA es terminal: nada reactiva a un paciente dado de alta.
if estadoPrevio == 'ALTA' then
    return 2
end

-- 3) Un evento más antiguo que el último aplicado no puede retroceder el estado.
if ts < tsPrevio then
    return 3
end

local nivel = ARGV[2]
local estadoNuevo = ARGV[3]
local eraActivo = estadoPrevio and estadoPrevio ~= 'ALTA'
local esActivo = estadoNuevo ~= 'ALTA'

-- 4) Ajuste de contadores: se retira del nivel previo y se suma al nivel actual si sigue activo.
if eraActivo then
    redis.call('HINCRBY', KEYS[3], nivelPrevio, -1)
end
if esActivo then
    redis.call('HINCRBY', KEYS[3], nivel, 1)
end

redis.call('HSET', KEYS[2], 'nivel', nivel, 'estado', estadoNuevo, 'ts', ARGV[4])
if esActivo then
    redis.call('PERSIST', KEYS[2])
else
    -- Se conserva un tiempo (no se borra) para que el guard de ALTA siga rechazando eventos tardíos.
    redis.call('EXPIRE', KEYS[2], ARGV[5])
end

local actualizado = tonumber(redis.call('GET', KEYS[4]) or '-1')
if ts > actualizado then
    redis.call('SET', KEYS[4], ARGV[4])
end
return 1
