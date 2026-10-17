# ADR-0006: Seguridad: JWT, mínimo privilegio, red privada y mapeo OWASP Top 10:2025

- **Estado:** Aceptado
- **Fecha:** 2026-10-16

## Autenticación y autorización
- **JWT RS256** validado por Spring Security (OAuth2 Resource Server, WebFlux). La API solo tiene la
  **llave pública**: puede verificar tokens pero no emitirlos.
- Se validan `iss` (`https://auth.ticketing.local`), `aud` (`ticketing-api`), `exp`, `nbf` y la firma,
  con un clock skew de 30 s.
- Scopes: `ticketing:user` (comprar, consultar y confirmar sus órdenes) y `ticketing:admin` (crear y editar
  eventos, emitir cortesías y consultar cualquier orden).
- Local: un script genera el par de llaves (ignorado por git) y emite tokens de prueba, simulando un IdP.
  En AWS sería **Amazon Cognito** o el IdP corporativo con JWKS, y API Gateway con un JWT authorizer.
- **Acceso por propiedad:** una orden solo la ven y confirman su dueño (`userId = sub`) o un admin. A
  cualquier otro usuario se le responde `404` para no revelar que la orden existe.

## Secretos
- No hay secretos en el código ni en las imágenes. En local se usa `.env` (ignorado) y `.env.example`.
- Los secretos de aplicación (API key de la pasarela de pago) viven en **Secrets Manager** y se leen al
  arrancar mediante el adapter `SECRETS` del scaffold, con caché.
- En AWS no hay access keys: cada componente usa su **rol IAM** (task role / execution role de Lambda).

## Red (AWS, en Terraform)
- VPC con **subnets privadas** únicamente para el cómputo; sin IGW ni NAT hacia los servicios.
- Acceso a AWS por **VPC endpoints**: gateway para DynamoDB e interface para SQS, Secrets Manager, KMS,
  ECR, Logs y STS.
- La única entrada es **API Gateway** con **WAF**, que llega por **VPC Link** a un **NLB interno**.
- Security groups de mínimo privilegio: el NLB solo hacia el puerto 8080 de la API; la API y el processor
  solo hacia el SG de los endpoints por 443.
- Local: la red Docker `ticketing-internal` es `internal: true`; solo el API publica un puerto
  (`127.0.0.1:8090`).

### IP del cliente para el rate limit (proxies de confianza)
Detrás del NLB la conexión TCP llega desde el balanceador, no desde el cliente: usar esa dirección como
llave haría que todos los clientes compartieran un único bucket (un abusador a 50 req/s dejaría en 429
todas las lecturas públicas). Por eso API Gateway **sobrescribe** el header `X-Client-Ip` con
`context.identity.sourceIp` en la integración (un valor enviado por el cliente nunca llega al servicio), y
el servicio solo lo lee cuando el peer TCP pertenece a `ticketing.rate-limit.trusted-proxies` (en AWS, los
CIDR de las subnets privadas del NLB, vía `TICKETING_RATE_LIMIT_TRUSTED_PROXIES`). Desde un peer no
confiable el header se ignora, y un valor que no es un literal IP (sin resolver DNS) se descarta: en ambos
casos la llave es la dirección del peer. Por defecto la lista está vacía y el comportamiento es el de
siempre (local con docker-compose).

## OWASP Top 10:2025: controles

| Categoría | Controles |
|---|---|
| A01 Broken Access Control | Scopes por endpoint, verificación de propiedad de la orden, 404 en lugar de 403 para órdenes ajenas, deny-by-default en Spring Security |
| A02 Security Misconfiguration | Actuator en un puerto interno con solo `health`/`info`/`prometheus`; headers de seguridad (HSTS, `X-Content-Type-Options`, `Cache-Control: no-store`); sin CORS abierto; nada público en la red |
| A03 Software Supply Chain Failures | Versiones fijadas (imágenes por digest); Dependabot; OWASP dependency-check en CI; imagen base mínima y usuario no root |
| A04 Cryptographic Failures | TLS en tránsito (API Gateway y endpoints); SSE-KMS en DynamoDB y SQS; JWT RS256 |
| A05 Injection | Bean Validation en todas las entradas; expresiones de DynamoDB siempre con placeholders (`:v`, `#n`); validación de los mensajes SQS |
| A06 Insecure Design | Idempotencia, límite de entradas por orden, rate limiting, máquina de estados explícita con invariantes |
| A07 Authentication Failures | Validación completa del JWT, tokens de vida corta, sin sesiones en el servidor |
| A08 Software or Data Integrity Failures | Mensajes delgados: el consumidor relee desde la fuente de verdad; el `requestHash` detecta manipulación del payload |
| A09 Security Logging & Alerting Failures | Auditoría de cada transición; logs JSON con `traceId`; alarmas sobre la DLQ y los errores de la Lambda; sin datos sensibles en los logs |
| A10 Mishandling of Exceptional Conditions | Manejador global reactivo; Problem Details sin internals; fail-closed (si no hay certeza, no se vende); timeouts en todas las llamadas |
