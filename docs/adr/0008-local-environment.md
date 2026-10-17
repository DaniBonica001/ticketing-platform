# ADR-0008: Entorno local con LocalStack 4.14 (community) y docker-compose

- **Estado:** Aceptado
- **Fecha:** 2026-10-16

## Contexto
- Desde 2026 la imagen `localstack/localstack:latest` exige `LOCALSTACK_AUTH_TOKEN`. La versión
  **4.14.0** es la última community que arranca sin cuenta.
- 4.14.0 incluye DynamoDB, SQS, Lambda (runtime `java25` verificado), EventBridge, IAM, KMS, Secrets
  Manager, CloudWatch/Logs, API Gateway y EC2 (VPC). **No** incluye ECS, ELBv2 ni WAF (son Pro).
- El PDF exige que un `docker-compose.yml` levante todo.

## Decisión
- `docker-compose.yml` en este repo levanta: LocalStack (fijado por digest), `otel-lgtm`, `ms-ticketing-api`
  y `ms-order-processor`, más un servicio de un solo uso `aws-bootstrap` que crea tablas, colas, secretos,
  la Lambda y la regla de EventBridge con `awslocal`.
- Así el repo de la aplicación es **autosuficiente** para el evaluador (`docker compose up`).
- El repo **ticketing-infra** (Terraform) define la infraestructura **de AWS real**, modular; el entorno
  `envs/local` se aplica contra LocalStack con `tflocal` para los servicios soportados. Los módulos que
  necesitan servicios Pro (ECS, ALB/NLB, WAF) se validan con `terraform validate`/`plan`.
- Los puertos evitan otros stacks locales (ver [convenciones](../design/conventions.md#4-puertos-local)).

## Consecuencias
- ✅ Se evalúa con un solo comando y sin cuentas.
- ⚠️ La infraestructura queda descrita dos veces (script de bootstrap y Terraform); el bootstrap se limita
  a lo mínimo necesario para ejecutar el sistema.
- ⚠️ Las VPC y los SG de LocalStack son simulados: el aislamiento real en local lo dan las redes Docker.
