# ADR-0005: Expiración de reservas y reconciliación con Lambda + EventBridge

- **Estado:** Aceptado
- **Fecha:** 2026-10-16

## Contexto
- Las reservas duran como máximo 10 minutos; si no se confirman, el inventario debe volver
  **periódicamente** a `available`.
- La API escribe en DynamoDB y después publica en SQS (*dual write*). Si el proceso muere entre las dos
  operaciones, la orden queda en `QUEUED` sin mensaje.

## Decisión
1. **Vencimiento lógico exacto:** `deadlineAt = reservedAt + 600 s`. Confirmar exige `deadlineAt > now`
   dentro de la misma condición. Una reserva vencida **no se puede confirmar**, aunque el expirer todavía
   no haya pasado.
2. **Liberación física periódica:** una **regla de EventBridge `rate(1 minute)`** invoca la **Lambda**
   `ticketing-reservation-expirer`, que:
   - consulta `status-deadlineAt-index` con `status = RESERVED AND deadlineAt < now` (paginado);
   - aplica T7 (`RESERVED → EXPIRED`, `reserved → available`) a cada orden;
   - **reconcilia**: las órdenes `QUEUED` y `PENDING_CONFIRMATION` con `deadlineAt` vencido se re-publican
     en su cola y se mueve su `deadlineAt` +5 min (R1/R2). Es seguro porque los consumidores son idempotentes.
3. El índice es **disperso**: solo contiene órdenes no finales, así que la consulta es barata.

## Alternativas consideradas
| Alternativa | Motivo del descarte |
|---|---|
| DynamoDB TTL | Borra con un retraso de hasta ~48 h, **elimina** la orden (y el PDF exige poder consultarla) y no devuelve el inventario |
| Mensaje SQS con `DelaySeconds=600` por reserva | Es preciso, pero suma piezas y aun así necesita un barrido de respaldo. Queda como **mejora** |
| Un schedule de EventBridge Scheduler por orden | Miles de schedules en un pico; tiene cuotas y costo |
| Micro con `@Scheduled` | Cómputo ocioso 24/7 y necesita leader election con varias réplicas |

## Consecuencias
- ✅ La regla de los 10 minutos se cumple exactamente; el inventario vuelve en ≤ 1 min.
- ✅ La reconciliación cierra el hueco del *dual write* sin necesidad de un outbox.
- ✅ Costo: ~1 440 invocaciones al día, dentro de la capa gratuita.
- ⚠️ Si la Lambda falla de forma sostenida, el inventario tarda en volver. Se cubre con una alarma sobre los
  errores de la Lambda y la edad del ítem más antiguo en el índice.
- ⚠️ Las ejecuciones podrían solaparse si una tarda más de 1 min. Son seguras gracias a las condiciones; además
  se configura `reserved concurrency = 1`.
