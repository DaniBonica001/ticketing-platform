# ADR-0001: Tres componentes desacoplados por colas

- **Estado:** Aceptado
- **Fecha:** 2026-10-16

## Contexto
El sistema actual colapsa en picos de demanda (timeouts) y sobrevende. El enunciado pide una API
reactiva que **encole** las compras y delegue el procesamiento pesado a **consumidores asíncronos**, más un
proceso **periódico** que libere reservas vencidas.

## Decisión
Tres componentes, cada uno generado con el scaffold de Clean Architecture de Bancolombia:

| Componente | Runtime | Responsabilidad |
|---|---|---|
| `ms-ticketing-api` | Contenedor (ECS Fargate en AWS) | API REST reactiva: eventos, disponibilidad (GET + SSE), crear/consultar/confirmar órdenes, cortesías. Solo hace escrituras rápidas y encola |
| `ms-order-processor` | Contenedor (ECS Fargate en AWS) | Consume `orders-queue` (reserva) y `confirmations-queue` (pago → venta). Aplica las transiciones con escrituras condicionales |
| `ms-reservation-expirer` | **AWS Lambda** disparada por **EventBridge** cada minuto | Expira reservas vencidas y re-publica órdenes atascadas (reconciliación) |

No se construye un API Gateway propio: en AWS se usa **Amazon API Gateway** (con WAF y VPC Link hacia
un NLB interno), definido en Terraform.

## Alternativas consideradas
- **Monolito modular:** más simple, pero no permite escalar la API y los consumidores por separado, que es
  justo lo que exige un pico de demanda.
- **Expirer como micro siempre encendido (`@Scheduled`):** paga cómputo ocioso 24/7 y necesita
  coordinación (leader election) si hay más de una réplica.
- **Expirer como tarea programada de ECS:** el arranque de Fargate (30–60 s) es desproporcionado para una
  tarea de segundos que corre cada minuto.
- **Gateway propio (Spring Cloud Gateway):** código sin valor diferencial frente al servicio administrado.

## Consecuencias
- ✅ La API absorbe los picos: responde 202 en milisegundos y la cola amortigua la carga.
- ✅ Los consumidores escalan por profundidad de cola, independientes de la API.
- ✅ El expirer solo cuesta los segundos que corre, sin coordinación entre réplicas.
- ⚠️ Consistencia eventual: el cliente consulta (o escucha por SSE) el estado de su orden.
- ⚠️ Cold start de Java en Lambda; en AWS se mitiga con SnapStart.
