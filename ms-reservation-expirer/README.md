# ms-reservation-expirer

AWS Lambda (`java25`) `ticketing-reservation-expirer`, invocada cada minuto por la regla EventBridge
`ticketing-reservation-expirer-schedule`. Implementa T7 (`RESERVED → EXPIRED`) y la reconciliación R1/R2
([ADR-0005](../docs/adr/0005-reservation-expiry-and-reconciliation.md)).

| Pieza | Valor |
|---|---|
| Handler | `com.dbonilla.ticketing.expirer.lambda.ExpirerHandler::handleRequest` |
| Entrada / salida | evento programado de EventBridge (`Map<String,Object>`) → `{expired, skipped, republishedQueued, republishedPendingConfirmation, failures}` |
| Empaquetado | `./gradlew buildLambdaZip` → `applications/app-service/build/distributions/ms-reservation-expirer.zip` (clases en la raíz + `lib/*.jar`) |
| Build + gate de cobertura (90 % de líneas por módulo) | `./gradlew clean build buildLambdaZip` |

## Variables de entorno

| Variable | Obligatoria | Default |
|---|---|---|
| `EVENTS_TABLE`, `ORDERS_TABLE`, `AUDIT_TABLE` | sí | — |
| `ORDERS_QUEUE_URL`, `CONFIRMATIONS_QUEUE_URL` | sí | — |
| `AWS_REGION` | sí (Lambda la define sola) | — |
| `AWS_ENDPOINT_URL` | solo local (LocalStack) | — |
| `RESERVATION_TTL_SECONDS` | no | `600` (solo informativa: el `deadlineAt` ya viene en la orden) |
| `RECONCILIATION_DEADLINE_SECONDS` | no | `300` |
| `EXPIRER_PAGE_SIZE` | no | `100` (`Limit` de cada página del GSI, máx. 1000) |
| `EXPIRER_CONCURRENCY` | no | `4` (órdenes en paralelo por estado, máx. 32) |
| `LOG_LEVEL` | no | `INFO` |

## Decisiones

- **Sin contexto Spring:** el cableado es manual (`bootstrap/LambdaComponentsProvider`) para bajar el cold start;
  la Lambda no necesita servidor web, auto-configuración ni escaneo de componentes. El scaffold sigue aportando
  el BOM de versiones; los artefactos de Spring se excluyen de los classpaths de producción.
- El entry point no depende de los driven adapters: descubre la raíz de composición con `ServiceLoader`
  (`META-INF/services`). Los clientes se crean una vez por entorno de ejecución (fase *init*) y se reutilizan.
- Clientes **async** del AWS SDK v2 con un único cliente HTTP Netty compartido. `block()` se usa solo en
  `ExpirerHandler`, porque el contrato de Lambda es síncrono.
- Reintentos: los del SDK están desactivados; la única política es `Retry.backoff(3, 100ms).maxBackoff(2s).jitter(0.5)`
  filtrada por `TransientErrors.isTransient` (helper `retry`). Las fallas condicionales nunca se reintentan.
- Un error en una orden se registra (log JSON) y se cuenta en `failures`; el resto continúa. El paginado se corta
  cuando quedan menos de 10 s de ejecución.
- R1/R2 publican primero y después mueven `deadlineAt = now + RECONCILIATION_DEADLINE_SECONDS`
  (condición `status = <mismo>`): un fallo entre ambos pasos solo produce un duplicado inofensivo.

Clases excluidas del gate de cobertura: `bootstrap/AwsClientFactory` (solo construye clientes del SDK; la cubre el smoke test en LocalStack).

---

# Proyecto Base Implementando Clean Architecture

## Antes de Iniciar

Empezaremos por explicar los diferentes componentes del proyectos y partiremos de los componentes externos, continuando con los componentes core de negocio (dominio) y por último el inicio y configuración de la aplicación.

Lee el artículo [Clean Architecture — Aislando los detalles](https://medium.com/bancolombia-tech/clean-architecture-aislando-los-detalles-4f9530f35d7a)

# Arquitectura

![Clean Architecture](https://miro.medium.com/max/1400/1*ZdlHz8B0-qu9Y-QO3AXR_w.png)

## Domain

Es el módulo más interno de la arquitectura, pertenece a la capa del dominio y encapsula la lógica y reglas del negocio mediante modelos y entidades del dominio.

## Usecases

Este módulo gradle perteneciente a la capa del dominio, implementa los casos de uso del sistema, define lógica de aplicación y reacciona a las invocaciones desde el módulo de entry points, orquestando los flujos hacia el módulo de entities.

## Infrastructure

### Helpers

En el apartado de helpers tendremos utilidades generales para los Driven Adapters y Entry Points.

Estas utilidades no están arraigadas a objetos concretos, se realiza el uso de generics para modelar comportamientos
genéricos de los diferentes objetos de persistencia que puedan existir, este tipo de implementaciones se realizan
basadas en el patrón de diseño [Unit of Work y Repository](https://medium.com/@krzychukosobudzki/repository-design-pattern-bc490b256006)

Estas clases no puede existir solas y debe heredarse su compartimiento en los **Driven Adapters**

### Driven Adapters

Los driven adapter representan implementaciones externas a nuestro sistema, como lo son conexiones a servicios rest,
soap, bases de datos, lectura de archivos planos, y en concreto cualquier origen y fuente de datos con la que debamos
interactuar.

### Entry Points

Los entry points representan los puntos de entrada de la aplicación o el inicio de los flujos de negocio.

## Application

Este módulo es el más externo de la arquitectura, es el encargado de ensamblar los distintos módulos, resolver las dependencias y crear los beans de los casos de use (UseCases) de forma automática, inyectando en éstos instancias concretas de las dependencias declaradas. Además inicia la aplicación (es el único módulo del proyecto donde encontraremos la función “public static void main(String[] args)”.

**Los beans de los casos de uso se disponibilizan automaticamente gracias a un '@ComponentScan' ubicado en esta capa.**
