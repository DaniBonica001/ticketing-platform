#!/usr/bin/env bash
# Customer managed KMS key used for SSE on DynamoDB, SQS and Secrets Manager (ADR-0003 / dynamodb.md).
# Sourced by bootstrap.sh. Sets KMS_KEY_ARN (empty when ENABLE_KMS=false).

readonly KMS_ALIAS="alias/ticketing-local"

KMS_KEY_ARN=""

ensure_kms_key() {
  if [[ "${ENABLE_KMS}" != "true" ]]; then
    log "KMS disabled (ENABLE_KMS=${ENABLE_KMS}); resources use service-managed encryption"
    return 0
  fi

  local key_id
  key_id="$(awsl kms list-aliases --query "Aliases[?AliasName=='${KMS_ALIAS}'].TargetKeyId | [0]")"
  if [[ -z "${key_id}" || "${key_id}" == "None" ]]; then
    key_id="$(awsl kms create-key \
      --description "ticketing local encryption key" \
      --tags "TagKey=${PROJECT_TAG_KEY},TagValue=${PROJECT_TAG_VALUE}" \
      --query 'KeyMetadata.KeyId')"
    awsl kms create-alias --alias-name "${KMS_ALIAS}" --target-key-id "${key_id}"
    log "created KMS key ${key_id} (${KMS_ALIAS})"
  else
    log "KMS key ${KMS_ALIAS} already exists (${key_id})"
  fi

  # shellcheck disable=SC2034 # read by dynamodb.sh, sqs.sh and secrets.sh
  KMS_KEY_ARN="$(awsl kms describe-key --key-id "${key_id}" --query 'KeyMetadata.Arn')"
}
