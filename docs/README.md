# Documentación técnica

| Documento | Contenido |
|---|---|
| [Convenciones](design/conventions.md) | Componentes, versiones, nombres de recursos, puertos y variables de entorno |
| [Modelo de dominio](design/domain-model.md) | Estados de la entrada y de la orden, transiciones e invariantes |
| [Diseño de DynamoDB](design/dynamodb.md) | Tablas, GSI y transacciones exactas |
| [Errores y reintentos](design/error-handling-and-retries.md) | Matriz de reintentos, abuso y catálogo de errores HTTP |
| [Diagrama de arquitectura](diagrams/architecture.png) | AWS con zonas de red y controles de seguridad ([fuente Excalidraw](diagrams/architecture.excalidraw)) |
| [Diagramas de secuencia](diagrams/sequence-diagrams.md) | 8 flujos en Mermaid |
| [OpenAPI](../contracts/openapi/ticketing-api.yaml) | Contrato REST de `ms-ticketing-api` |
| [Mensajes SQS](../contracts/messages/order-message.schema.json) | JSON Schema de los mensajes |

## Decisiones de arquitectura (ADR)

| ADR | Decisión |
|---|---|
| [0001](adr/0001-architecture-components.md) | Tres componentes desacoplados por colas |
| [0002](adr/0002-concurrency-conditional-writes.md) | Escrituras condicionales para el inventario; optimistic locking para editar eventos |
| [0003](adr/0003-async-processing-sqs.md) | SQS estándar + DLQ + consumidores idempotentes |
| [0004](adr/0004-idempotency.md) | Idempotencia con UUID v5 |
| [0005](adr/0005-reservation-expiry-and-reconciliation.md) | Expiración y reconciliación con Lambda + EventBridge |
| [0006](adr/0006-security.md) | Seguridad: JWT, red privada y OWASP Top 10:2025 |
| [0007](adr/0007-observability-otel.md) | Observabilidad con OpenTelemetry y Grafana LGTM |
| [0008](adr/0008-local-environment.md) | Entorno local con LocalStack 4.14 y docker-compose |
