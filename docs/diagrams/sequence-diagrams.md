# Diagramas de secuencia

Notación: `Tn` y `Rn` son las transiciones y reconciliaciones del [modelo de dominio](../design/domain-model.md).

## 1. Compra exitosa (camino feliz)

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant API as ms-ticketing-api
    participant DB as DynamoDB
    participant QO as orders-queue
    participant QC as confirmations-queue
    participant P as ms-order-processor
    participant PG as PaymentGateway

    U->>API: POST /api/v1/orders {eventId, quantity}<br/>Authorization + Idempotency-Key
    API->>API: valida JWT, payload, rate limit<br/>orderId = UUIDv5(userId:key)
    API->>DB: T1 Tx: Put order QUEUED [attribute_not_exists] + audit
    API->>QO: SendMessage OrderPlaced {orderId} (traceparent)
    API-->>U: 202 {orderId, status: QUEUED}

    QO->>P: ReceiveMessage (long polling)
    P->>DB: GetItem order (fuente de verdad)
    P->>DB: T2 Tx: event available-=q, reserved+=q [available>=q]<br/>order QUEUED→RESERVED, deadlineAt=now+600 + audit
    P->>QO: DeleteMessage

    U->>API: GET /api/v1/orders/{orderId}
    API->>DB: GetItem (ConsistentRead)
    API-->>U: 200 {status: RESERVED, expiresAt}

    U->>API: POST /api/v1/orders/{orderId}/confirm {paymentReference}
    API->>DB: T4 Tx: order RESERVED→PENDING_CONFIRMATION<br/>[status=RESERVED ∧ deadlineAt>now ∧ userId=caller]<br/>event reserved-=q, pendingConfirmation+=q + audit
    API->>QC: SendMessage OrderConfirmationRequested {orderId}
    API-->>U: 202 {status: PENDING_CONFIRMATION}

    QC->>P: ReceiveMessage
    P->>PG: verify(paymentReference)
    PG-->>P: APPROVED
    P->>DB: T5 Tx: order →SOLD, event pendingConfirmation-=q, sold+=q + audit
    P->>QC: DeleteMessage
```

## 2. Sin cupo (sobreventa evitada)

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant API as ms-ticketing-api
    participant QO as orders-queue
    participant P as ms-order-processor
    participant DB as DynamoDB

    U->>API: POST /orders {quantity: 2}
    API->>DB: T1 order QUEUED
    API->>QO: OrderPlaced
    API-->>U: 202 {status: QUEUED}
    QO->>P: ReceiveMessage
    P->>DB: T2 Tx [available >= 2]
    DB-->>P: TransactionCanceled: ConditionalCheckFailed (events)
    Note over P: Regla de negocio: NO se reintenta
    P->>DB: T3 Tx: order QUEUED→REJECTED(SOLD_OUT) [status=QUEUED] + audit
    P->>QO: DeleteMessage
    U->>API: GET /orders/{id}
    API-->>U: 200 {status: REJECTED, reason: SOLD_OUT}
```

## 3. Reintento del cliente (idempotencia)

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant API as ms-ticketing-api
    participant DB as DynamoDB

    U->>API: POST /orders, Idempotency-Key: K1
    API->>DB: T1 Put [attribute_not_exists] → OK
    API-->>U: 202 {orderId: O1}
    U->>API: POST /orders, Idempotency-Key: K1 (reintento)
    API->>DB: T1 Put [attribute_not_exists]
    DB-->>API: ConditionalCheckFailed + ALL_OLD (O1)
    API->>API: requestHash igual
    API-->>U: 202 {orderId: O1, status actual}
    U->>API: POST /orders, Idempotency-Key: K1, otro payload
    API->>DB: T1 Put → ConditionalCheckFailed
    API->>API: requestHash distinto
    API-->>U: 422 IDEMPOTENCY_KEY_REUSED
```

## 4. Caída del processor, mensaje duplicado y DLQ

```mermaid
sequenceDiagram
    autonumber
    participant QO as orders-queue
    participant P1 as processor #1
    participant P2 as processor #2
    participant DB as DynamoDB
    participant DLQ as orders-dlq

    QO->>P1: ReceiveMessage O2 (invisible 30 s, receiveCount=1)
    P1->>DB: T2 Tx → OK (O2 RESERVED)
    Note over P1: 💥 se cae antes de DeleteMessage
    Note over QO: a los 30 s el mensaje vuelve a ser visible
    QO->>P2: ReceiveMessage O2 (receiveCount=2)
    P2->>DB: T2 Tx [order status=QUEUED]
    DB-->>P2: ConditionalCheckFailed (orders) → ya procesada
    P2->>QO: DeleteMessage (duplicado inofensivo)

    Note over QO,DLQ: Mensaje envenenado (falla siempre)
    loop receiveCount 1..5
        QO->>P2: ReceiveMessage X
        P2--xP2: error no controlado → no se borra
    end
    QO->>DLQ: redrive (maxReceiveCount=5)
    Note over DLQ: Alarma: ApproximateNumberOfMessagesVisible > 0
```

## 5. Expiración y carrera con la confirmación

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant API as ms-ticketing-api
    participant EB as EventBridge rate(1 min)
    participant L as Lambda expirer
    participant DB as DynamoDB

    Note over DB: O2 RESERVED, deadlineAt = 10:11:00
    U->>API: 10:11:30 POST /orders/O2/confirm
    API->>DB: T4 Tx [deadlineAt > now]
    DB-->>API: ConditionalCheckFailed
    API-->>U: 409 RESERVATION_EXPIRED

    EB->>L: 10:12:00 invoke
    L->>DB: Query GSI status=RESERVED ∧ deadlineAt < now
    DB-->>L: [O2]
    L->>DB: T7 Tx: order RESERVED→EXPIRED [status=RESERVED ∧ deadlineAt<now]<br/>event reserved-=q, available+=q + audit
    L-->>EB: done (la ejecución termina)

    Note over U,DB: Carrera: confirm y expirer al mismo tiempo sobre O3
    par
        API->>DB: T4 [status=RESERVED ∧ deadlineAt>now]
    and
        L->>DB: T7 [status=RESERVED ∧ deadlineAt<now]
    end
    Note over DB: deadlineAt hace excluyentes las condiciones<br/>y status=RESERVED garantiza un único ganador
```

## 6. Pago rechazado

```mermaid
sequenceDiagram
    autonumber
    participant QC as confirmations-queue
    participant P as ms-order-processor
    participant PG as PaymentGateway
    participant DB as DynamoDB

    QC->>P: OrderConfirmationRequested O4
    P->>PG: verify(paymentReference)
    PG-->>P: DECLINED
    P->>DB: T6 Tx: order →REJECTED(PAYMENT_DECLINED)<br/>event pendingConfirmation-=q, available+=q + audit
    P->>QC: DeleteMessage

    Note over P,PG: Pasarela caída (timeout / 5xx)
    QC->>P: OrderConfirmationRequested O5
    P->>PG: verify → timeout (reintentos con backoff)
    Note over P: circuit breaker abierto → no se borra el mensaje
    Note over QC: vuelve tras el VisibilityTimeout, tras 5 intentos va a la DLQ
```

## 7. Reconciliación del dual write (DynamoDB OK, SQS falló)

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant API as ms-ticketing-api
    participant DB as DynamoDB
    participant QO as orders-queue
    participant L as Lambda expirer
    participant P as ms-order-processor

    U->>API: POST /orders
    API->>DB: T1 order QUEUED, deadlineAt = now+300
    API-xQO: SendMessage falla 3 veces
    API-->>U: 202 {status: QUEUED} (la orden existe)
    Note over L: 5 min después
    L->>DB: Query GSI status=QUEUED ∧ deadlineAt < now
    L->>QO: R1 SendMessage OrderPlaced (re-publicación)
    L->>DB: deadlineAt += 300 [status=QUEUED]
    QO->>P: ReceiveMessage → T2 normal
```

## 8. Disponibilidad en tiempo real (SSE)

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant API as ms-ticketing-api
    participant DB as DynamoDB

    C->>API: GET /api/v1/events/{id}/availability/stream (text/event-stream)
    loop cada 2 s (Flux.interval)
        API->>DB: GetItem event (ConsistentRead)
        API->>API: distinctUntilChanged
        API-->>C: event: availability {available, reserved, pendingConfirmation, sold, complimentary}
    end
    Note over API,C: heartbeat cada 15 s, la conexión se cierra a los 5 min (el cliente reconecta)
```
