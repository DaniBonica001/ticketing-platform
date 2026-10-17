# Convenciones del proyecto

Fuente única de verdad para nombres, versiones, puertos y variables de entorno.
Todo componente (micros, Lambda, docker-compose, Terraform) **debe** respetar este documento.

> Idioma: la documentación está en español; **todo el código, identificadores, comentarios,
> contratos (OpenAPI, JSON Schema) y mensajes de log están en inglés**.

## 1. Componentes

| Componente | Carpeta | Paquete base | Tipo | Scaffold |
|---|---|---|---|---|
| Ticketing API | `ms-ticketing-api/` | `com.dbonilla.ticketing.api` | Spring Boot WebFlux | `gradle ca --type=REACTIVE --java-version=25 --name=MsTicketingApi` |
| Order processor | `ms-order-processor/` | `com.dbonilla.ticketing.processor` | Spring Boot (consumidor SQS, sin servidor web público) | `gradle ca --type=REACTIVE --java-version=25 --name=MsOrderProcessor` |
| Reservation expirer | `ms-reservation-expirer/` | `com.dbonilla.ticketing.expirer` | AWS Lambda (runtime `java25`) | `gradle ca --type=REACTIVE --java-version=25 --name=MsReservationExpirer` |

Cada micro es un proyecto Gradle **independiente** (su propio `settings.gradle`, wrapper y build),
generado con el plugin `co.com.bancolombia.cleanArchitecture` **5.0.1** (Spring Boot 4.1.1).
No hay código compartido entre micros: cada uno modela su propio dominio (bounded context);
los contratos compartidos son los de `contracts/`.

### Módulos del scaffold por componente

| Componente | Driven adapters (`gradle gda`) | Entry points (`gradle gep`) |
|---|---|---|
| ms-ticketing-api | `--type=DYNAMODB`, `--type=SQS` (sender) | `--type=WEBFLUX` |
| ms-order-processor | `--type=DYNAMODB`, `--type=SECRETS`, `--type=GENERIC --name=payment-gateway` | `--type=SQS` (listener) |
| ms-reservation-expirer | `--type=DYNAMODB`, `--type=SQS` (sender, para reconciliación) | `--type=GENERIC --name=lambda-handler` |

## 2. Versiones fijadas

| Pieza | Versión |
|---|---|
| Java | 25 (toolchain) |
| Scaffold Bancolombia | 5.0.1 |
| Spring Boot | 4.1.1 (gestionada por el scaffold) |
| Gradle | 9.8.1 (wrapper generado) |
| AWS SDK | v2, clientes **async** (`DynamoDbAsyncClient`, `SqsAsyncClient`) |
| LocalStack | `localstack/localstack:4.14.0` (community, sin auth token) |
| Observabilidad | `grafana/otel-lgtm:0.36.0` |
| Terraform | >= 1.16, provider `hashicorp/aws` ~> 6.x |

## 3. Recursos AWS (nombres exactos)

Región: `us-east-1`. Cuenta LocalStack: `000000000000`. Prefijo: `ticketing-`.

| Recurso | Nombre |
|---|---|
| Tabla de eventos | `ticketing-events` |
| Tabla de órdenes | `ticketing-orders` |
| GSI de órdenes | `status-deadlineAt-index` |
| Tabla de auditoría | `ticketing-order-audit` |
| Cola de órdenes | `ticketing-orders-queue` |
| DLQ de órdenes | `ticketing-orders-dlq` |
| Cola de confirmaciones | `ticketing-confirmations-queue` |
| DLQ de confirmaciones | `ticketing-confirmations-dlq` |
| Secreto de la pasarela de pago | `ticketing/payment-gateway` (JSON `{"apiKey": "..."}`) |
| Lambda | `ticketing-reservation-expirer` |
| Regla EventBridge | `ticketing-reservation-expirer-schedule` (`rate(1 minute)`) |

## 4. Puertos (local)

Elegidos para **no chocar** con otros stacks locales que usan 4566–4599, 3000 y 8086.

| Servicio | Puerto host | Puerto contenedor | Expuesto |
|---|---|---|---|
| ms-ticketing-api | `127.0.0.1:8090` | 8080 | Sí (única entrada del stack) |
| Actuator (todos los micros) | — | 9090 | No, solo red interna |
| LocalStack | `127.0.0.1:4766` | 4566 | Solo loopback, para depuración |
| Grafana | `127.0.0.1:3300` | 3000 | Solo loopback |
| OTLP gRPC / HTTP | — | 4317 / 4318 | No, solo red interna |

Redes Docker: `ticketing-edge` (API ↔ host) y `ticketing-internal` (`internal: true`: API, processor,
LocalStack, OTel; sin salida a internet).

## 5. Variables de entorno

| Variable | Usada por | Ejemplo local |
|---|---|---|
| `AWS_REGION` | todos | `us-east-1` |
| `AWS_ENDPOINT_URL` | todos (solo local; en AWS no se define) | `http://localstack:4566` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | todos (solo local, valores dummy `test`) | `test` |
| `EVENTS_TABLE` | api, processor, expirer | `ticketing-events` |
| `ORDERS_TABLE` | api, processor, expirer | `ticketing-orders` |
| `AUDIT_TABLE` | api, processor, expirer | `ticketing-order-audit` |
| `ORDERS_QUEUE_URL` | api, expirer | `http://localstack:4566/000000000000/ticketing-orders-queue` |
| `CONFIRMATIONS_QUEUE_URL` | api, expirer | `http://localstack:4566/000000000000/ticketing-confirmations-queue` |
| `PAYMENT_GATEWAY_SECRET_ID` | processor | `ticketing/payment-gateway` |
| `JWT_ISSUER` | api | `https://auth.ticketing.local` |
| `JWT_AUDIENCE` | api | `ticketing-api` |
| `JWT_PUBLIC_KEY_LOCATION` | api | `file:/run/secrets/jwt-public.pem` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | todos | `http://otel-lgtm:4318` |
| `OTEL_SERVICE_NAME` | todos | `ms-ticketing-api` |

El processor recibe `ORDERS_QUEUE_URL` y `CONFIRMATIONS_QUEUE_URL` porque es quien las consume.

**Ningún secreto se versiona.** Los valores locales viven en `.env` (ignorado por git) y el repo
incluye `.env.example`.

## 6. Parámetros de negocio

| Parámetro | Valor | Propiedad |
|---|---|---|
| Duración de la reserva | 10 min (600 s) | `ticketing.reservation.ttl=PT10M` |
| Plazo de reconciliación de `QUEUED` / `PENDING_CONFIRMATION` | 5 min (300 s) | `ticketing.reconciliation.deadline=PT5M` |
| Entradas por orden | 1 – 10 | validación en la API |
| Capacidad de un evento | 1 – 100 000 | validación en la API |
| Frecuencia del expirer | 1 min | regla EventBridge |
| Reintentos transitorios (DynamoDB/SQS) | 3, backoff exponencial desde 100 ms, jitter 0.5 | `ticketing.retry.*` |
| `maxReceiveCount` (redrive a DLQ) | 5 | cola SQS |

## 7. Convenciones de código

- Dominio con `record`s inmutables y `sealed interface` para errores; transiciones con
  `switch` + pattern matching. Sin Lombok (`--lombok=FALSE`).
- Todo I/O devuelve `Mono`/`Flux`; está prohibido `block()` fuera de tests.
- Errores de negocio como excepciones de dominio tipadas, traducidas a
  Problem Details (RFC 9457) solo en el entry point.
- Logs estructurados JSON (Spring Boot structured logging, formato `ecs`) con `traceId`/`spanId`.
  Nunca se loguean tokens, secretos ni el payload completo de la petición.
- Tests: JUnit 5, Mockito, reactor-test (`StepVerifier`). JaCoCo con **gate del 90%** por micro.
- Commits: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`...).
