#!/usr/bin/env bash
# DynamoDB tables from docs/design/dynamodb.md. Sourced by bootstrap.sh.

readonly ORDERS_GSI_NAME="status-deadlineAt-index"

table_exists() {
  awsl dynamodb describe-table --table-name "$1" >/dev/null 2>&1
}

sse_args() {
  if [[ -n "${KMS_KEY_ARN}" ]]; then
    printf '%s\n' "--sse-specification" "Enabled=true,SSEType=KMS,KMSMasterKeyId=${KMS_KEY_ARN}"
  fi
}

# create_table <name> <extra create-table args...>
create_table() {
  local name="$1"
  shift
  if table_exists "${name}"; then
    log "table ${name} already exists"
    return 0
  fi

  local -a sse=()
  mapfile -t sse < <(sse_args)

  awsl dynamodb create-table \
    --table-name "${name}" \
    --billing-mode PAY_PER_REQUEST \
    --tags "Key=${PROJECT_TAG_KEY},Value=${PROJECT_TAG_VALUE}" \
    "${sse[@]}" \
    "$@" >/dev/null
  awsl dynamodb wait table-exists --table-name "${name}"
  log "created table ${name}"
}

ensure_tables() {
  create_table "${EVENTS_TABLE}" \
    --attribute-definitions AttributeName=eventId,AttributeType=S \
    --key-schema AttributeName=eventId,KeyType=HASH

  # The GSI is sparse: final orders drop deadlineAt and disappear from the index.
  create_table "${ORDERS_TABLE}" \
    --attribute-definitions \
      AttributeName=orderId,AttributeType=S \
      AttributeName=status,AttributeType=S \
      AttributeName=deadlineAt,AttributeType=N \
    --key-schema AttributeName=orderId,KeyType=HASH \
    --global-secondary-indexes \
      "IndexName=${ORDERS_GSI_NAME},KeySchema=[{AttributeName=status,KeyType=HASH},{AttributeName=deadlineAt,KeyType=RANGE}],Projection={ProjectionType=ALL}"

  create_table "${AUDIT_TABLE}" \
    --attribute-definitions \
      AttributeName=orderId,AttributeType=S \
      AttributeName=transitionId,AttributeType=S \
    --key-schema AttributeName=orderId,KeyType=HASH AttributeName=transitionId,KeyType=RANGE
}
