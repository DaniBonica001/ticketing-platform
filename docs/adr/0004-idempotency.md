# ADR-0004: Idempotencia de la creación de órdenes con UUID v5

- **Estado:** Aceptado
- **Fecha:** 2026-10-16

## Contexto
Los clientes reintentan (mala señal, timeouts, doble clic) y un atacante puede reenviar peticiones. El
sistema actual sufre **compras duplicadas**.

## Decisión
- `POST /orders` exige el header `Idempotency-Key` (UUID).
- `orderId = UUIDv5(NAMESPACE_TICKETING, userId + ":" + idempotencyKey)`: **determinista**.
- La orden se crea con `Put ... CONDITION attribute_not_exists(orderId)`.
- Se guarda `requestHash = SHA-256(eventId + ":" + quantity)`.

| Caso | Resultado |
|---|---|
| Primera petición | Crea la orden y responde `202` |
| Reintento idéntico | La condición falla, se lee la orden existente y se responde `202` con su estado actual |
| Misma key con otro payload | `422 IDEMPOTENCY_KEY_REUSED` |
| Misma key pero otro usuario | Genera otro `orderId` (el `userId` forma parte del hash): no hay colisión entre usuarios |

`POST /orders/{id}/confirm` es idempotente por estado: si la orden ya está en `PENDING_CONFIRMATION`
con la misma `paymentReference`, responde `202` sin repetir el pago.

## Alternativas consideradas
- **Tabla `idempotency-keys` aparte:** es una escritura y una tabla más para lograr lo mismo.
- **GSI sobre `idempotencyKey`:** los GSI son eventualmente consistentes, así que dos reintentos simultáneos
  podrían crear dos órdenes.

## Consecuencias
- ✅ Una sola escritura condicional y fuertemente consistente.
- ✅ No hay ventana de expiración de la key: la orden *es* el registro de idempotencia.
- ⚠️ El `orderId` deriva del userId y la key, pero no revela ninguno de los dos (es un hash SHA-1 con namespace).
