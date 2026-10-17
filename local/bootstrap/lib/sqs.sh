#!/usr/bin/env bash
# Standard SQS queues with DLQs, parameters from ADR-0003. Sourced by bootstrap.sh.

readonly QUEUE_VISIBILITY_TIMEOUT_SECONDS=30
readonly QUEUE_RECEIVE_WAIT_SECONDS=20
readonly QUEUE_RETENTION_SECONDS=345600 # 4 days
readonly DLQ_RETENTION_SECONDS=1209600  # 14 days
readonly QUEUE_MAX_RECEIVE_COUNT=5

# Prints the queue URL, creating the queue when missing.
ensure_queue_exists() {
  local name="$1"
  local url
  if url="$(awsl sqs get-queue-url --queue-name "${name}" --query QueueUrl 2>/dev/null)"; then
    log "queue ${name} already exists" >&2
  else
    url="$(awsl sqs create-queue --queue-name "${name}" \
      --tags "${PROJECT_TAG_KEY}=${PROJECT_TAG_VALUE}" --query QueueUrl)"
    log "created queue ${name}" >&2
  fi
  printf '%s' "${url}"
}

encryption_attributes() {
  if [[ -n "${KMS_KEY_ARN}" ]]; then
    printf '"KmsMasterKeyId":"%s","KmsDataKeyReusePeriodSeconds":"300"' "${KMS_KEY_ARN}"
  else
    printf '"SqsManagedSseEnabled":"true"'
  fi
}

# Attributes are (re)applied on every run so the queues converge to the documented configuration.
ensure_queue_with_dlq() {
  local name="$1"
  local dlq_name="$2"
  local dlq_url dlq_arn queue_url redrive

  dlq_url="$(ensure_queue_exists "${dlq_name}")"
  awsl sqs set-queue-attributes --queue-url "${dlq_url}" --attributes \
    "{\"MessageRetentionPeriod\":\"${DLQ_RETENTION_SECONDS}\",$(encryption_attributes)}"
  dlq_arn="$(awsl sqs get-queue-attributes --queue-url "${dlq_url}" \
    --attribute-names QueueArn --query 'Attributes.QueueArn')"

  queue_url="$(ensure_queue_exists "${name}")"
  redrive="{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"${QUEUE_MAX_RECEIVE_COUNT}\\\"}"
  awsl sqs set-queue-attributes --queue-url "${queue_url}" --attributes \
    "{\"VisibilityTimeout\":\"${QUEUE_VISIBILITY_TIMEOUT_SECONDS}\",\"ReceiveMessageWaitTimeSeconds\":\"${QUEUE_RECEIVE_WAIT_SECONDS}\",\"MessageRetentionPeriod\":\"${QUEUE_RETENTION_SECONDS}\",\"RedrivePolicy\":\"${redrive}\",$(encryption_attributes)}"

  log "queue ${name} configured (DLQ ${dlq_name}, maxReceiveCount=${QUEUE_MAX_RECEIVE_COUNT})"
}

ensure_queues() {
  ensure_queue_with_dlq "ticketing-orders-queue" "ticketing-orders-dlq"
  ensure_queue_with_dlq "ticketing-confirmations-queue" "ticketing-confirmations-dlq"
}
