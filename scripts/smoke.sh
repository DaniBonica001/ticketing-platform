#!/usr/bin/env bash
# Smoke test of the running local stack: health endpoints plus the AWS resources created by
# aws-bootstrap, queried through LocalStack's loopback port (127.0.0.1:4766).
# Uses awslocal when it works, otherwise the aws CLI with --endpoint-url.
set -euo pipefail

readonly LOCALSTACK_URL="${LOCALSTACK_URL:-http://127.0.0.1:4766}"
readonly GRAFANA_URL="${GRAFANA_URL:-http://127.0.0.1:3300}"
readonly REGION="${AWS_REGION:-us-east-1}"
readonly MANAGEMENT_HEALTH="http://localhost:9090/actuator/health/liveness"

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_PAGER=""

FAILURES=0

section() {
  printf '\n== %s\n' "$*"
}

check() {
  local label="$1"
  shift
  if "$@"; then
    printf '  [ OK ] %s\n' "${label}"
  else
    printf '  [FAIL] %s\n' "${label}"
    FAILURES=$((FAILURES + 1))
  fi
}

aws_local() {
  if LOCALSTACK_HOST="${LOCALSTACK_URL#http://}" awslocal --version >/dev/null 2>&1; then
    LOCALSTACK_HOST="${LOCALSTACK_URL#http://}" awslocal --region "${REGION}" "$@"
  elif command -v aws >/dev/null; then
    aws --endpoint-url "${LOCALSTACK_URL}" --region "${REGION}" "$@"
  else
    echo "neither awslocal nor aws CLI is available" >&2
    return 1
  fi
}

http_ok() {
  curl -fsS -m 5 -o /dev/null "$1"
}

service_running() {
  [[ -n "$(docker compose ps --status running -q "$1" 2>/dev/null)" ]]
}

app_live() {
  docker compose exec -T "$1" wget -qO- "${MANAGEMENT_HEALTH}" 2>/dev/null | grep -q '"UP"'
}

check_apps() {
  section "Applications (liveness on the internal management port 9090)"
  local service
  for service in ms-ticketing-api ms-order-processor; do
    if service_running "${service}"; then
      check "${service} liveness" app_live "${service}"
    else
      printf '  [SKIP] %s is not running\n' "${service}"
    fi
  done
}

check_infrastructure() {
  section "Infrastructure"
  check "LocalStack health (${LOCALSTACK_URL})" http_ok "${LOCALSTACK_URL}/_localstack/health"
  check "Grafana health (${GRAFANA_URL})" http_ok "${GRAFANA_URL}/api/health"
  check "Grafana dashboard 'Ticketing' provisioned" http_ok "${GRAFANA_URL}/api/dashboards/uid/ticketing"
}

aws_text() {
  aws_local "$@" --output text
}

gsi_present() {
  aws_text dynamodb describe-table --table-name ticketing-orders \
    --query 'Table.GlobalSecondaryIndexes[].IndexName' | grep -qw status-deadlineAt-index
}

table_present() {
  aws_text dynamodb describe-table --table-name "$1" --query 'Table.TableStatus' | grep -qx ACTIVE
}

redrive_present() {
  aws_text sqs get-queue-attributes --queue-url "${LOCALSTACK_URL}/000000000000/$1" \
    --attribute-names RedrivePolicy --query 'Attributes.RedrivePolicy' | grep -q "$2"
}

secret_present() {
  aws_text secretsmanager describe-secret --secret-id ticketing/payment-gateway --query Name >/dev/null
}

lambda_active() {
  aws_text lambda get-function --function-name ticketing-reservation-expirer \
    --query 'Configuration.State' | grep -qx Active
}

rule_enabled() {
  aws_text events describe-rule --name ticketing-reservation-expirer-schedule \
    --query '[ScheduleExpression,State]' | grep -q 'rate(1 minute).*ENABLED'
}

check_aws_resources() {
  section "AWS resources (LocalStack ${LOCALSTACK_URL})"
  local table
  for table in ticketing-events ticketing-orders ticketing-order-audit; do
    check "table ${table} ACTIVE" table_present "${table}"
  done
  check "GSI status-deadlineAt-index on ticketing-orders" gsi_present
  check "ticketing-orders-queue redrives to ticketing-orders-dlq" \
    redrive_present ticketing-orders-queue ticketing-orders-dlq
  check "ticketing-confirmations-queue redrives to ticketing-confirmations-dlq" \
    redrive_present ticketing-confirmations-queue ticketing-confirmations-dlq
  check "secret ticketing/payment-gateway" secret_present
  check "Lambda ticketing-reservation-expirer Active" lambda_active
  check "rule ticketing-reservation-expirer-schedule rate(1 minute) ENABLED" rule_enabled

  section "Inventory"
  aws_text sqs list-queues --query 'QueueUrls[]' | tr '\t' '\n' | sed 's/^/  queue:  /'
  aws_text events list-targets-by-rule --rule ticketing-reservation-expirer-schedule \
    --query 'Targets[].Arn' | sed 's/^/  target: /'
}

main() {
  check_infrastructure
  check_apps
  check_aws_resources
  printf '\n'
  if ((FAILURES > 0)); then
    echo "Smoke test FAILED (${FAILURES} check(s))."
    exit 1
  fi
  echo "Smoke test passed."
}

main "$@"
