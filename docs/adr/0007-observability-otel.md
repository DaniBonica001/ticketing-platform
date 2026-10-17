# ADR-0007: Observabilidad con OpenTelemetry (OTLP) y Grafana LGTM

- **Estado:** Aceptado
- **Fecha:** 2026-10-16

## Decisión
- Estándar **OpenTelemetry**: todos los componentes exportan trazas, métricas y logs por **OTLP**
  (`spring-boot-starter-opentelemetry` + Micrometer en Spring Boot 4).
- Local: un solo contenedor **`grafana/otel-lgtm`** (Collector + Prometheus + Tempo + Loki + Grafana), con
  Grafana en `127.0.0.1:3300` y un dashboard provisionado.
- **Propagación del contexto por SQS:** la API inyecta `traceparent` (W3C) como atributo del mensaje y el
  processor lo extrae. Una compra se ve como **una sola traza** API → cola → processor.
- Logs JSON con `traceId`/`spanId` para saltar del log a la traza.

## Métricas de negocio
| Métrica | Tipo |
|---|---|
| `ticketing.orders.created` | counter |
| `ticketing.orders.transitions{from,to}` | counter |
| `ticketing.orders.rejected{reason}` | counter |
| `ticketing.reservations.expired` | counter |
| `ticketing.dynamodb.transaction.conflicts` | counter |
| `ticketing.order.processing.duration` | timer |

Además se exponen las métricas estándar de HTTP, JVM y de los clientes del AWS SDK.

## En AWS (producción)
ADOT Collector → CloudWatch (métricas y logs) y X-Ray (trazas), o Amazon Managed Prometheus y Grafana.
Alarmas: profundidad y edad de la DLQ, `ApproximateAgeOfOldestMessage`, errores de la Lambda, 5xx y
latencia p99 de la API, y throttling de DynamoDB.

## Por qué no un stack completo (Prometheus + Tempo + Loki por separado)
Para una prueba local, `otel-lgtm` ofrece lo mismo con un solo contenedor. Como el código solo conoce
OTLP, cambiar de backend es configuración y no código.
