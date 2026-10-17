# Manejo de errores y reintentos

## 1. Principio

> **Se reintenta solo lo que puede salir bien al reintentar.** Un error transitorio (red, throttling,
> contención) se reintenta con backoff exponencial y jitter, y siempre de forma acotada. Un error de
> negocio o de validación **nunca** se reintenta.

Todos los reintentos son seguros porque todas las escrituras son **idempotentes**: cada transición
exige el estado origen (ver [modelo de dominio](domain-model.md)).

## 2. Matriz de reintentos

| Dónde | Error | ¿Reintenta? | Estrategia |
|---|---|---|---|
| Cualquier llamada a DynamoDB/SQS | `ProvisionedThroughputExceeded`, `ThrottlingException`, `RequestLimitExceeded`, 5xx, timeout, `IOException` | ✅ | `Retry.backoff(3, 100ms).maxBackoff(2s).jitter(0.5).filter(isTransient)` |
| Transacción DynamoDB | `TransactionCanceled` con motivo `TransactionConflict` | ✅ | Igual que arriba. Es la contención típica sobre el ítem caliente del evento |
| Transacción DynamoDB | `ConditionalCheckFailed` en el **evento** (sin cupo) | ❌ | Regla de negocio: `REJECTED (SOLD_OUT)` |
| Transacción DynamoDB | `ConditionalCheckFailed` en la **orden** | ❌ | Ya procesada (duplicado) o estado inválido: ack o 409 |
| Editar evento | `ConditionalCheckFailed` por `version` | ❌ | `412 VERSION_CONFLICT`; el cliente relee y decide |
| API, entrada inválida | validación, JWT inválido | ❌ | 400 / 401 / 403 inmediato |
| API → SQS (`SendMessage`) tras crear la orden | transitorio | ✅ 3 veces | Si se agotan los intentos, la orden queda `QUEUED` y el **expirer la re-publica** (R1). El cliente recibe 202 igualmente |
| Processor (cualquier fallo no controlado) | excepción | ✅ vía SQS | No se borra el mensaje: reaparece tras el `VisibilityTimeout` (30 s). Con `maxReceiveCount = 5` pasa a la **DLQ** |
| Processor → pasarela de pago | timeout / 5xx | ✅ | Backoff + **circuit breaker** (Resilience4j). Con el circuito abierto, el mensaje vuelve a la cola |
| Processor → pasarela de pago | pago rechazado | ❌ | `REJECTED (PAYMENT_DECLINED)` |
| Expirer (Lambda) | fallo en una orden | ❌ en la misma ejecución | Se registra el error y se continúa con las demás. La siguiente ejecución (1 min) la vuelve a encontrar |
| Cliente → API | reintento de `POST /orders` | ✅ seguro | Mismo `Idempotency-Key` = misma orden (ver ADR-0004) |
| Cliente → API | `429` / `503` | ✅ | Respetar el header `Retry-After` |

`isTransient` es un predicado único por micro, en `infrastructure/helpers`, y está cubierto por tests.

## 3. Reintentos maliciosos y abuso

| Amenaza | Control |
|---|---|
| Reenvío masivo del mismo POST | `Idempotency-Key` obligatorio: no se crean órdenes nuevas |
| Mismo key con otro payload | `requestHash` → `422 IDEMPOTENCY_KEY_REUSED` |
| Acaparar inventario con muchas órdenes | Máximo 10 entradas por orden; rate limit por usuario en `POST /orders` (5 req/s, ráfaga 10) |
| Inundar endpoints públicos | Rate limit por IP (50 req/s). En AWS se suman el throttling de API Gateway y una regla de WAF basada en tasa |
| Payloads gigantes | Límite de cuerpo de 16 KB → `413` |
| Mensaje envenenado en SQS | DLQ tras 5 intentos + alarma; el consumidor valida el esquema |
| Doble confirmación / doble cobro | Condición `status = RESERVED` en T4; si ya está `PENDING_CONFIRMATION` con la misma `paymentReference`, responde 202 idempotente |

## 4. Catálogo de errores HTTP

Formato **RFC 9457 Problem Details** (`application/problem+json`), con la extensión `code`:

```json
{ "type": "https://ticketing.local/problems/reservation-expired", "title": "Reservation expired",
  "status": 409, "code": "RESERVATION_EXPIRED", "detail": "The reservation for order 3f2c… expired",
  "instance": "/api/v1/orders/3f2c…/confirm", "traceId": "4bf92f3577b34da6a3ce929d0e0e4736" }
```

| HTTP | `code` | Cuándo |
|---|---|---|
| 400 | `VALIDATION_ERROR` | payload, header o parámetro inválido (incluye `errors[]` por campo) |
| 401 | `UNAUTHORIZED` | JWT ausente, inválido o vencido |
| 403 | `FORBIDDEN` | scope insuficiente |
| 404 | `EVENT_NOT_FOUND` | el evento no existe |
| 404 | `ORDER_NOT_FOUND` | la orden no existe **o no pertenece al usuario** (no se revela cuál de los dos) |
| 409 | `INVALID_ORDER_STATE` | transición no permitida desde el estado actual |
| 409 | `RESERVATION_EXPIRED` | confirmación después del `deadlineAt` |
| 409 | `INSUFFICIENT_INVENTORY` | cortesía sin cupo (las compras se rechazan de forma asíncrona, no aquí) |
| 412 | `VERSION_CONFLICT` | `If-Match` no coincide con la versión del evento |
| 413 | `PAYLOAD_TOO_LARGE` | cuerpo > 16 KB |
| 422 | `IDEMPOTENCY_KEY_REUSED` | misma key con un payload distinto |
| 428 | `PRECONDITION_REQUIRED` | falta `If-Match` en la edición del evento |
| 429 | `RATE_LIMITED` | límite superado (incluye `Retry-After`) |
| 503 | `SERVICE_UNAVAILABLE` | dependencia caída tras agotar los reintentos (incluye `Retry-After`) |
| 500 | `INTERNAL_ERROR` | inesperado. **Nunca** incluye el stack trace ni el mensaje interno |

Los errores 5xx se loguean con su contexto completo (traceId, orderId, causa); al cliente solo llega el
Problem Details genérico (OWASP A10).
