# Diseño de DynamoDB

Tres tablas, una por patrón de acceso. Todas en modo **on-demand** (`PAY_PER_REQUEST`), con cifrado
en reposo (KMS) y Point-In-Time Recovery activado (en Terraform).

Convención de tiempos:
- `createdAt`, `updatedAt`, `occurredAt`: string ISO-8601 UTC con milisegundos (`2026-10-16T10:00:01.123Z`).
- `deadlineAt`: **número** (segundos epoch), porque se compara en condiciones y es clave del GSI.

## 1. `ticketing-events`

| Atributo | Tipo | Notas |
|---|---|---|
| `eventId` (PK) | S | UUID v4 |
| `name` | S | 1–120 caracteres |
| `eventDate` | S | ISO-8601 con offset |
| `venue` | S | 1–120 caracteres |
| `capacity` | N | 1–100 000 |
| `available`, `reserved`, `pendingConfirmation`, `sold`, `complimentary` | N | contadores de inventario |
| `version` | N | optimistic locking, **solo** para editar datos del evento |
| `createdAt`, `updatedAt` | S | |

```json
{ "eventId": "8b0e…", "name": "Karol G - Tropicoqueta Tour", "eventDate": "2026-12-20T20:00:00-05:00",
  "venue": "Estadio Atanasio Girardot", "capacity": 5, "available": 3, "reserved": 0,
  "pendingConfirmation": 0, "sold": 2, "complimentary": 0, "version": 1,
  "createdAt": "2026-10-16T09:00:00.000Z", "updatedAt": "2026-10-16T10:12:00.000Z" }
```

Accesos: `GetItem` por `eventId` (detalle y disponibilidad); `Scan` paginado para listar (ver
limitaciones en el README).

## 2. `ticketing-orders`

| Atributo | Tipo | Notas |
|---|---|---|
| `orderId` (PK) | S | **UUID v5** de `userId + ":" + idempotencyKey` (ver [ADR-0004](../adr/0004-idempotency.md)) |
| `eventId` | S | |
| `userId` | S | claim `sub` del JWT |
| `quantity` | N | 1–10 |
| `status` | S | `QUEUED`, `RESERVED`, `PENDING_CONFIRMATION`, `SOLD`, `COMPLIMENTARY`, `REJECTED`, `EXPIRED` |
| `reason` | S | solo en `REJECTED`: `SOLD_OUT` / `PAYMENT_DECLINED` |
| `deadlineAt` | N | solo en estados no finales (ver modelo de dominio) |
| `requestHash` | S | SHA-256 del cuerpo canónico; detecta reuso de la key con otro payload |
| `paymentReference` | S | desde la confirmación |
| `createdAt`, `updatedAt` | S | |

**GSI `status-deadlineAt-index`**: PK `status` (S), SK `deadlineAt` (N), proyección `ALL`.
Es **disperso**: las órdenes finales no tienen `deadlineAt`, así que no aparecen en el índice.

Accesos:
- `GetItem` por `orderId` (consulta de estado).
- `Query` en el GSI: `status = :s AND deadlineAt < :now` (expirer), paginado con `Limit`.

## 3. `ticketing-order-audit`

Solo inserción (append-only). Nunca se actualiza ni se borra.

| Atributo | Tipo | Notas |
|---|---|---|
| `orderId` (PK) | S | |
| `transitionId` (SK) | S | `<occurredAt>#<toStatus>`, p. ej. `2026-10-16T10:00:01.123Z#RESERVED` |
| `eventId` | S | |
| `fromStatus` | S | ausente en la creación |
| `toStatus` | S | |
| `quantity` | N | |
| `reason` | S | opcional |
| `actor` | S | `ticketing-api` / `order-processor` / `reservation-expirer` |
| `traceId` | S | correlación con las trazas OTel |
| `occurredAt` | S | |

Acceso: `Query` por `orderId` (historial ordenado por `transitionId`).

## 4. Transacciones exactas

Notación: `:now` = epoch actual en segundos; `:q` = cantidad de la orden.

### T1: crear orden (`ticketing-api`)
```
TransactWriteItems
  Put  orders   item{status=QUEUED, deadlineAt=:now+300, ...}
       Condition: attribute_not_exists(orderId)
       ReturnValuesOnConditionCheckFailure: ALL_OLD
  Put  audit    {toStatus=QUEUED, actor=ticketing-api}
```
Si la condición falla, la orden ya existe: si `requestHash` coincide se devuelve la existente;
si no, responde `422 IDEMPOTENCY_KEY_REUSED`.

### T2: reservar (`order-processor`)
```
TransactWriteItems
  Update events  SET available = available - :q, reserved = reserved + :q, updatedAt = :ts
                 Condition: available >= :q
  Update orders  SET #status = :RESERVED, deadlineAt = :now + 600, updatedAt = :ts
                 Condition: #status = :QUEUED
  Put    audit   {fromStatus=QUEUED, toStatus=RESERVED}
```
Interpretación de `TransactionCanceledException.CancellationReasons`:

| Falla en | Código | Significado | Acción |
|---|---|---|---|
| orders | `ConditionalCheckFailed` | ya procesada (mensaje duplicado) | ack (borrar mensaje) |
| events | `ConditionalCheckFailed` | sin cupo | ejecutar T3 y ack |
| cualquiera | `TransactionConflict` / `ThrottlingError` | contención transitoria | **reintentar** con backoff |

### T3: rechazar por falta de cupo
```
TransactWriteItems
  Update orders  SET #status = :REJECTED, reason = :SOLD_OUT, updatedAt = :ts  REMOVE deadlineAt
                 Condition: #status = :QUEUED
  Put    audit   {fromStatus=QUEUED, toStatus=REJECTED, reason=SOLD_OUT}
```

### T4: confirmar (`ticketing-api`)
```
TransactWriteItems
  Update orders  SET #status = :PENDING_CONFIRMATION, paymentReference = :ref,
                     deadlineAt = :now + 300, updatedAt = :ts
                 Condition: #status = :RESERVED AND deadlineAt > :now AND userId = :caller
  Update events  SET reserved = reserved - :q, pendingConfirmation = pendingConfirmation + :q
                 Condition: reserved >= :q
  Put    audit   {fromStatus=RESERVED, toStatus=PENDING_CONFIRMATION}
```

### T5 / T6: resultado del pago (`order-processor`)
```
T5 (aprobado):  orders  PENDING_CONFIRMATION → SOLD                       REMOVE deadlineAt
                events  pendingConfirmation -= :q, sold += :q
T6 (rechazado): orders  PENDING_CONFIRMATION → REJECTED(PAYMENT_DECLINED)  REMOVE deadlineAt
                events  pendingConfirmation -= :q, available += :q
Ambas: condición #status = :PENDING_CONFIRMATION en la orden y pendingConfirmation >= :q en el evento; + Put audit
```

### T7: expirar (`reservation-expirer`)
```
TransactWriteItems
  Update orders  SET #status = :EXPIRED, updatedAt = :ts  REMOVE deadlineAt
                 Condition: #status = :RESERVED AND deadlineAt < :now
  Update events  SET reserved = reserved - :q, available = available + :q
                 Condition: reserved >= :q
  Put    audit   {fromStatus=RESERVED, toStatus=EXPIRED, actor=reservation-expirer}
```
Si falla la condición de la orden, el usuario confirmó en ese mismo instante y la orden se ignora.

### T8: cortesía (`ticketing-api`, admin)
```
TransactWriteItems
  Update events  SET available = available - :q, complimentary = complimentary + :q
                 Condition: available >= :q
  Put    orders  {status=COMPLIMENTARY, userId=:recipient}  Condition: attribute_not_exists(orderId)
  Put    audit   {toStatus=COMPLIMENTARY, actor=ticketing-api}
```

### Editar evento (optimistic locking)
```
UpdateItem events SET name=:n, eventDate=:d, venue=:v, version = version + 1
              Condition: version = :expectedVersion
```
Si falla, responde `412 VERSION_CONFLICT`. La capacidad **no** se edita, porque rompería el
invariante del inventario.

## 5. Consideraciones

- **Item caliente:** todas las reservas de un evento actualizan el mismo ítem. Un ítem soporta del
  orden de 1 000 WCU/s, y las transacciones consumen el doble. Para eventos masivos la mejora es
  particionar el inventario (por sección o en N shards). Va documentado en Limitaciones.
- **Partición caliente del GSI:** todas las órdenes `RESERVED` comparten la clave `status`. Es aceptable
  porque el volumen de órdenes vivas es acotado (10 min). La mejora es una clave `status#shard`.
- Las lecturas de disponibilidad usan `ConsistentRead=true` sobre la tabla base (no sobre el GSI).
