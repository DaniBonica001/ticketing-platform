# ADR-0003: Procesamiento asíncrono con SQS estándar, DLQ y consumidores idempotentes

- **Estado:** Aceptado
- **Fecha:** 2026-10-16

## Contexto
Las compras deben encolarse de inmediato y procesarse de forma asíncrona con entrega **al menos una vez**
(at-least-once).

## Decisión
Dos colas **estándar**, cada una con su **DLQ**:

| Cola | Productor | Consumidor | Mensaje |
|---|---|---|---|
| `ticketing-orders-queue` | api, expirer (R1) | processor | `OrderPlaced` |
| `ticketing-confirmations-queue` | api, expirer (R2) | processor | `OrderConfirmationRequested` |

Configuración (igual para ambas):

| Parámetro | Valor | Por qué |
|---|---|---|
| `VisibilityTimeout` | 30 s | Mayor que el p99 del procesamiento más sus reintentos internos |
| `ReceiveMessageWaitTimeSeconds` | 20 s | Long polling: menos llamadas vacías y menos costo |
| `MessageRetentionPeriod` | 4 días (DLQ: 14 días) | Margen para recuperar ante incidentes |
| `RedrivePolicy.maxReceiveCount` | 5 | Aísla los mensajes envenenados sin bloquear la cola |
| Cifrado | SSE-KMS (CMK en AWS) | Datos en reposo |
| Política de la cola | Solo el rol productor tiene `SendMessage` y solo el processor `Receive/Delete/ChangeVisibility`; se niega todo acceso sin TLS (`aws:SecureTransport`) y fuera del VPC endpoint (`aws:SourceVpce`) | Mínimo privilegio |

Consumidor: recibe en lotes de 10 con long polling, procesa cada mensaje de forma independiente y lo
**borra solo si tuvo éxito** (o si es un duplicado o un rechazo de negocio). La concurrencia es acotada
(`flatMap` con un límite configurable) para no saturar DynamoDB.

**Mensajes delgados:** solo llevan `orderId` y `eventId`. El consumidor lee la orden desde DynamoDB, que
es la fuente de verdad, y no confía en cantidades ni montos que vengan en el mensaje (OWASP A08). El
contexto de traza viaja en el atributo `traceparent`.

## Por qué colas estándar y no FIFO
El orden y la exclusión ya los garantizan las condiciones de DynamoDB. FIFO limita el throughput y no
elimina los duplicados del lado del consumidor (por ejemplo, si el consumidor se cae antes del delete).

## Por qué dos colas y no una con un campo `type`
Si la pasarela de pago se pone lenta, las confirmaciones se acumulan **sin frenar las reservas**. Además
permite escalar, monitorear y dar permisos IAM por separado.

## Consecuencias
- ✅ At-least-once con idempotencia: los duplicados son inofensivos.
- ✅ Los mensajes que fallan siempre quedan en una DLQ con alarma.
- ⚠️ Doble escritura en la API (DynamoDB + SQS): se mitiga con la reconciliación (ADR-0005). La mejora en
  producción es un outbox con DynamoDB Streams + EventBridge Pipes.
