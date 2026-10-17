#!/usr/bin/env bash
# Shared helpers for the LocalStack bootstrap. Sourced by bootstrap.sh; not executable on its own.

# shellcheck disable=SC2034 # consumed by the other lib/*.sh files sourced by bootstrap.sh
readonly ACCOUNT_ID="000000000000"
# shellcheck disable=SC2034
readonly PROJECT_TAG_KEY="project"
# shellcheck disable=SC2034
readonly PROJECT_TAG_VALUE="ticketing"

log() {
  printf '[bootstrap] %s\n' "$*"
}

fail() {
  printf '[bootstrap] ERROR: %s\n' "$*" >&2
  exit 1
}

# Every AWS call goes through this wrapper so endpoint and output format are consistent.
awsl() {
  aws --endpoint-url "${AWS_ENDPOINT_URL}" --region "${AWS_REGION}" --output text "$@"
}

require_env() {
  local name
  for name in "$@"; do
    [[ -n "${!name:-}" ]] || fail "required environment variable ${name} is not set"
  done
}

wait_for_localstack() {
  local attempts=60
  local i
  for ((i = 1; i <= attempts; i++)); do
    if awsl sts get-caller-identity >/dev/null 2>&1; then
      log "LocalStack is reachable at ${AWS_ENDPOINT_URL}"
      return 0
    fi
    sleep 2
  done
  fail "LocalStack did not become reachable at ${AWS_ENDPOINT_URL}"
}
