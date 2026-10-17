#!/usr/bin/env bash
# Reservation expirer Lambda, its IAM role and the EventBridge schedule (ADR-0005). Sourced by bootstrap.sh.

readonly FUNCTION_NAME="ticketing-reservation-expirer"
# OpenTelemetry service name of the Lambda (conventions.md: component folder name).
readonly EXPIRER_SERVICE_NAME="ms-reservation-expirer"
readonly ROLE_NAME="ticketing-reservation-expirer"
readonly ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"
readonly RULE_NAME="ticketing-reservation-expirer-schedule"
readonly RULE_SCHEDULE="rate(1 minute)"
readonly LAMBDA_RUNTIME="java25"
readonly LAMBDA_HANDLER="com.dbonilla.ticketing.expirer.lambda.ExpirerHandler::handleRequest"
readonly LAMBDA_TIMEOUT_SECONDS=60
readonly LAMBDA_MEMORY_MB=1024
readonly LAMBDA_RESERVED_CONCURRENCY=1
readonly ARTIFACT_BUCKET="ticketing-lambda-artifacts"
readonly ARTIFACT_KEY="ms-reservation-expirer.zip"
readonly PERMISSION_SID="ticketing-reservation-expirer-schedule-invoke"
POLICIES_DIR="$(dirname "${BASH_SOURCE[0]}")/../policies"
readonly POLICIES_DIR

expirer_permissions_policy() {
  local table_prefix="arn:aws:dynamodb:${AWS_REGION}:${ACCOUNT_ID}:table"
  local queue_prefix="arn:aws:sqs:${AWS_REGION}:${ACCOUNT_ID}"
  cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["dynamodb:Query", "dynamodb:GetItem", "dynamodb:UpdateItem", "dynamodb:PutItem", "dynamodb:ConditionCheckItem"],
      "Resource": [
        "${table_prefix}/${EVENTS_TABLE}",
        "${table_prefix}/${ORDERS_TABLE}",
        "${table_prefix}/${ORDERS_TABLE}/index/${ORDERS_GSI_NAME}",
        "${table_prefix}/${AUDIT_TABLE}"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["sqs:SendMessage"],
      "Resource": ["${queue_prefix}:ticketing-orders-queue", "${queue_prefix}:ticketing-confirmations-queue"]
    },
    {
      "Effect": "Allow",
      "Action": ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"],
      "Resource": "arn:aws:logs:${AWS_REGION}:${ACCOUNT_ID}:log-group:/aws/lambda/${FUNCTION_NAME}:*"
    }
  ]
}
JSON
}

ensure_lambda_role() {
  if awsl iam get-role --role-name "${ROLE_NAME}" >/dev/null 2>&1; then
    log "IAM role ${ROLE_NAME} already exists"
  else
    awsl iam create-role --role-name "${ROLE_NAME}" \
      --assume-role-policy-document "file://${POLICIES_DIR}/lambda-trust.json" \
      --tags "Key=${PROJECT_TAG_KEY},Value=${PROJECT_TAG_VALUE}" >/dev/null
    log "created IAM role ${ROLE_NAME}"
  fi
  # put-role-policy is an upsert, so the inline policy always matches the current table names.
  awsl iam put-role-policy --role-name "${ROLE_NAME}" \
    --policy-name "${FUNCTION_NAME}-access" \
    --policy-document "$(expirer_permissions_policy)"
}

upload_artifact() {
  if ! awsl s3api head-bucket --bucket "${ARTIFACT_BUCKET}" >/dev/null 2>&1; then
    awsl s3api create-bucket --bucket "${ARTIFACT_BUCKET}" >/dev/null
    log "created artifact bucket ${ARTIFACT_BUCKET}"
  fi
  awsl s3 cp "${LAMBDA_ZIP_PATH}" "s3://${ARTIFACT_BUCKET}/${ARTIFACT_KEY}" --only-show-errors
}

# Lambda reports CodeSha256 as base64 of the raw SHA-256 digest.
zip_code_sha256() {
  local hex
  hex="$(sha256sum "${LAMBDA_ZIP_PATH}" | cut -d' ' -f1)"
  # shellcheck disable=SC2059,SC2001 # the format string is the \x-escaped digest by design
  printf "$(sed 's/../\\x&/g' <<<"${hex}")" | base64
}

lambda_environment() {
  # AWS_REGION is reserved in Lambda and injected by the runtime, so it is not set here.
  cat <<JSON
{"Variables":{
  "EVENTS_TABLE":"${EVENTS_TABLE}",
  "ORDERS_TABLE":"${ORDERS_TABLE}",
  "AUDIT_TABLE":"${AUDIT_TABLE}",
  "ORDERS_QUEUE_URL":"${ORDERS_QUEUE_URL}",
  "CONFIRMATIONS_QUEUE_URL":"${CONFIRMATIONS_QUEUE_URL}",
  "AWS_ENDPOINT_URL":"${AWS_ENDPOINT_URL}",
  "OTEL_EXPORTER_OTLP_ENDPOINT":"${OTEL_EXPORTER_OTLP_ENDPOINT}",
  "OTEL_SERVICE_NAME":"${EXPIRER_SERVICE_NAME}"
}}
JSON
}

create_function() {
  awsl lambda create-function \
    --function-name "${FUNCTION_NAME}" \
    --runtime "${LAMBDA_RUNTIME}" \
    --handler "${LAMBDA_HANDLER}" \
    --role "${ROLE_ARN}" \
    --code "S3Bucket=${ARTIFACT_BUCKET},S3Key=${ARTIFACT_KEY}" \
    --timeout "${LAMBDA_TIMEOUT_SECONDS}" \
    --memory-size "${LAMBDA_MEMORY_MB}" \
    --environment "$(lambda_environment)" \
    --tags "${PROJECT_TAG_KEY}=${PROJECT_TAG_VALUE}" >/dev/null
  awsl lambda wait function-active-v2 --function-name "${FUNCTION_NAME}"
  log "created Lambda ${FUNCTION_NAME}"
}

update_function() {
  local deployed_sha
  deployed_sha="$(awsl lambda get-function-configuration --function-name "${FUNCTION_NAME}" --query CodeSha256)"
  if [[ "${deployed_sha}" == "$(zip_code_sha256)" ]]; then
    log "Lambda ${FUNCTION_NAME} code unchanged"
  else
    awsl lambda update-function-code --function-name "${FUNCTION_NAME}" \
      --s3-bucket "${ARTIFACT_BUCKET}" --s3-key "${ARTIFACT_KEY}" >/dev/null
    awsl lambda wait function-updated-v2 --function-name "${FUNCTION_NAME}"
    log "updated Lambda ${FUNCTION_NAME} code"
  fi
  awsl lambda update-function-configuration \
    --function-name "${FUNCTION_NAME}" \
    --runtime "${LAMBDA_RUNTIME}" \
    --handler "${LAMBDA_HANDLER}" \
    --role "${ROLE_ARN}" \
    --timeout "${LAMBDA_TIMEOUT_SECONDS}" \
    --memory-size "${LAMBDA_MEMORY_MB}" \
    --environment "$(lambda_environment)" >/dev/null
  awsl lambda wait function-updated-v2 --function-name "${FUNCTION_NAME}"
}

ensure_function() {
  [[ -f "${LAMBDA_ZIP_PATH}" ]] || fail "Lambda artifact not found at ${LAMBDA_ZIP_PATH} (did expirer-builder run?)"
  upload_artifact
  if awsl lambda get-function --function-name "${FUNCTION_NAME}" >/dev/null 2>&1; then
    update_function
  else
    create_function
  fi
  # Overlapping runs are safe thanks to DynamoDB conditions, but concurrency 1 avoids wasted work (ADR-0005).
  awsl lambda put-function-concurrency --function-name "${FUNCTION_NAME}" \
    --reserved-concurrent-executions "${LAMBDA_RESERVED_CONCURRENCY}" >/dev/null
}

ensure_schedule() {
  local rule_arn function_arn
  # put-rule and put-targets are upserts.
  rule_arn="$(awsl events put-rule --name "${RULE_NAME}" \
    --schedule-expression "${RULE_SCHEDULE}" --state ENABLED \
    --description "Expires reservations and reconciles stuck orders" --query RuleArn)"
  function_arn="$(awsl lambda get-function --function-name "${FUNCTION_NAME}" --query Configuration.FunctionArn)"

  awsl events put-targets --rule "${RULE_NAME}" \
    --targets "Id=${FUNCTION_NAME},Arn=${function_arn}" >/dev/null

  if awsl lambda get-policy --function-name "${FUNCTION_NAME}" --query Policy 2>/dev/null |
    grep -q "${PERMISSION_SID}"; then
    log "invoke permission for ${RULE_NAME} already exists"
  else
    awsl lambda add-permission --function-name "${FUNCTION_NAME}" \
      --statement-id "${PERMISSION_SID}" \
      --action lambda:InvokeFunction \
      --principal events.amazonaws.com \
      --source-arn "${rule_arn}" >/dev/null
    log "granted EventBridge permission to invoke ${FUNCTION_NAME}"
  fi
  log "schedule ${RULE_NAME} (${RULE_SCHEDULE}) targets ${FUNCTION_NAME}"
}

ensure_expirer() {
  if [[ "${SKIP_LAMBDA}" == "true" ]]; then
    log "SKIP_LAMBDA=true: Lambda, role and schedule were not deployed"
    return 0
  fi
  ensure_lambda_role
  ensure_function
  ensure_schedule
}
