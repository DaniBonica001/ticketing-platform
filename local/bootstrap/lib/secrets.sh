#!/usr/bin/env bash
# Payment gateway secret, JSON {"apiKey": "..."}. Sourced by bootstrap.sh.

readonly API_KEY_PATTERN='^[A-Za-z0-9._~-]{8,128}$'
readonly GENERATED_KEY_BYTES=24

generate_api_key() {
  printf 'local-%s' "$(od -An -N"${GENERATED_KEY_BYTES}" -tx1 /dev/urandom | tr -d ' \n')"
}

ensure_payment_secret() {
  if awsl secretsmanager describe-secret --secret-id "${PAYMENT_GATEWAY_SECRET_ID}" >/dev/null 2>&1; then
    # Never overwrite: the value must stay stable across re-runs while the processor caches it.
    log "secret ${PAYMENT_GATEWAY_SECRET_ID} already exists (value left unchanged)"
    return 0
  fi

  local api_key="${PAYMENT_GATEWAY_API_KEY:-}"
  if [[ -z "${api_key}" ]]; then
    api_key="$(generate_api_key)"
    log "PAYMENT_GATEWAY_API_KEY not set; generated a random local value"
  fi
  # The pattern also guarantees the value cannot break out of the JSON string below.
  [[ "${api_key}" =~ ${API_KEY_PATTERN} ]] ||
    fail "PAYMENT_GATEWAY_API_KEY must match ${API_KEY_PATTERN}"

  local -a kms=()
  [[ -n "${KMS_KEY_ARN}" ]] && kms=(--kms-key-id "${KMS_KEY_ARN}")

  awsl secretsmanager create-secret \
    --name "${PAYMENT_GATEWAY_SECRET_ID}" \
    --description "Payment gateway credentials (local)" \
    --secret-string "{\"apiKey\":\"${api_key}\"}" \
    --tags "Key=${PROJECT_TAG_KEY},Value=${PROJECT_TAG_VALUE}" \
    "${kms[@]}" >/dev/null
  log "created secret ${PAYMENT_GATEWAY_SECRET_ID}"
}
