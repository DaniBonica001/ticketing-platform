#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR/lib
# Idempotent LocalStack bootstrap for the ticketing stack (ADR-0008).
# Runs inside the one-shot `aws-bootstrap` compose service; safe to re-run at any time
# (`make bootstrap`). Resource names follow docs/design/conventions.md §3.
#
# Environment:
#   AWS_ENDPOINT_URL, AWS_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY   LocalStack access
#   EVENTS_TABLE, ORDERS_TABLE, AUDIT_TABLE, ORDERS_QUEUE_URL, CONFIRMATIONS_QUEUE_URL
#   PAYMENT_GATEWAY_SECRET_ID      secret name
#   PAYMENT_GATEWAY_API_KEY        optional; a random local value is generated when empty
#   OTEL_EXPORTER_OTLP_ENDPOINT    passed to the Lambda
#   LAMBDA_ZIP_PATH                Lambda artifact (default /artifacts/ms-reservation-expirer.zip)
#   ENABLE_KMS                     true|false (default true): SSE-KMS with a local CMK
#   SKIP_LAMBDA                    true|false (default false): skip Lambda, role and schedule
set -euo pipefail

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)"
readonly LIB_DIR

: "${LAMBDA_ZIP_PATH:=/artifacts/ms-reservation-expirer.zip}"
: "${ENABLE_KMS:=true}"
: "${SKIP_LAMBDA:=false}"

source "${LIB_DIR}/common.sh"
source "${LIB_DIR}/kms.sh"
source "${LIB_DIR}/dynamodb.sh"
source "${LIB_DIR}/sqs.sh"
source "${LIB_DIR}/secrets.sh"
source "${LIB_DIR}/lambda.sh"

main() {
  require_env AWS_ENDPOINT_URL AWS_REGION EVENTS_TABLE ORDERS_TABLE AUDIT_TABLE \
    ORDERS_QUEUE_URL CONFIRMATIONS_QUEUE_URL PAYMENT_GATEWAY_SECRET_ID OTEL_EXPORTER_OTLP_ENDPOINT

  wait_for_localstack
  ensure_kms_key
  ensure_tables
  ensure_queues
  ensure_payment_secret
  ensure_expirer
  log "done"
}

main "$@"
