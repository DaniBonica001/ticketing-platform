# ADR-0002: Escrituras condicionales para el inventario y optimistic locking para editar eventos

- **Estado:** Aceptado
- **Fecha:** 2026-10-16

## Contexto
Miles de compras simultáneas sobre el mismo evento no deben generar sobreventa. El patrón "leer, decidir
y escribir" tiene una condición de carrera: dos procesos leen el mismo `available` y ambos venden. El
enunciado pide *optimistic locking* o *conditional writes* y operaciones **sin bloqueos**.

## Decisión
1. **Inventario:** escrituras condicionales **sin lectura previa**:
   `SET available = available - :q ... CONDITION available >= :q`. DynamoDB evalúa la condición contra el
   valor actual de forma atómica, serializando las escrituras sobre el ítem.
2. **Atomicidad orden + inventario + auditoría:** `TransactWriteItems` (todo o nada).
3. **Edición de datos del evento** (nombre, fecha, lugar): **optimistic locking** con `version`
   (`If-Match` → `CONDITION version = :v`).

## Por qué no usar `version` también en el inventario
Con optimistic locking, si 1 000 compradores leen `version = 12`, solo uno escribe. Los 999 restantes
fallan **aunque haya cupo** y deben releer y reintentar: una tormenta de reintentos justo en el pico. Con
la condición sobre `available`, solo fallan las compras que realmente no caben.

## Alternativas consideradas
- **Locks pesimistas / distribuidos (Redis, tabla de locks):** contradicen "sin bloqueos" y agregan un
  punto de falla.
- **Una fila por entrada (asiento):** necesaria solo con selección de asiento; multiplica las escrituras.
- **Cola FIFO por evento para serializar:** limita el throughput y no evita las carreras con el expirer.

## Consecuencias
- ✅ Cero sobreventa por construcción; probado con un test de concurrencia (N compradores, M entradas).
- ✅ Sin locks ni lecturas previas en el camino caliente.
- ⚠️ El ítem del evento es un **hot key** (~1 000 WCU/s, y las transacciones cuestan el doble). Mejora:
  particionar el inventario (secciones o shards).
- ⚠️ Bajo contención aparecen `TransactionConflict`: se reintentan con backoff y jitter.
