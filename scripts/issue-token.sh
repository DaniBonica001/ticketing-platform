#!/usr/bin/env bash
# Issues an RS256 JWT signed with the local dev key (simulated IdP, ADR-0006). Prints only the token.
#
#   scripts/issue-token.sh <sub> <user|admin> [ttlSeconds]
#
#   user  -> scope "ticketing:user"
#   admin -> scope "ticketing:admin ticketing:user"
#
# Environment overrides: JWT_ISSUER, JWT_AUDIENCE, JWT_PRIVATE_KEY_FILE.
# Requires only bash and openssl.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
# shellcheck source-path=SCRIPTDIR
source "${SCRIPT_DIR}/lib/jwt.sh"

readonly DEFAULT_TTL_SECONDS=900 # 15 minutes
readonly MAX_TTL_SECONDS=86400
readonly SUB_PATTERN='^[A-Za-z0-9._@:-]{1,128}$'
readonly ISSUER="${JWT_ISSUER:-https://auth.ticketing.local}"
readonly AUDIENCE="${JWT_AUDIENCE:-ticketing-api}"
readonly PRIVATE_KEY="${JWT_PRIVATE_KEY_FILE:-${SCRIPT_DIR}/../local/secrets/jwt-private.pem}"
readonly KEY_ID="ticketing-dev"

usage() {
  printf 'Usage: %s <sub> <user|admin> [ttlSeconds]\n' "$(basename "$0")" >&2
  exit 2
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

scope_for_role() {
  case "$1" in
    user) printf 'ticketing:user' ;;
    admin) printf 'ticketing:admin ticketing:user' ;;
    *) die "role must be 'user' or 'admin' (got '$1')" ;;
  esac
}

main() {
  [[ $# -ge 2 && $# -le 3 ]] || usage
  local sub="$1"
  local role="$2"
  local ttl="${3:-${DEFAULT_TTL_SECONDS}}"

  # Validation also guarantees the values cannot break out of the JSON strings below.
  [[ "${sub}" =~ ${SUB_PATTERN} ]] || die "sub must match ${SUB_PATTERN}"
  if ! [[ "${ttl}" =~ ^[0-9]+$ ]] || ((ttl < 1 || ttl > MAX_TTL_SECONDS)); then
    die "ttlSeconds must be an integer between 1 and ${MAX_TTL_SECONDS}"
  fi
  [[ -r "${PRIVATE_KEY}" ]] || die "private key not found at ${PRIVATE_KEY}; run 'make keys' first"

  local scope now jti header payload signing_input signature
  scope="$(scope_for_role "${role}")"
  now="$(date +%s)"
  jti="$(openssl rand -hex 16)"

  header="$(printf '{"alg":"RS256","typ":"JWT","kid":"%s"}' "${KEY_ID}" | b64url_encode)"
  payload="$(printf '{"iss":"%s","aud":"%s","sub":"%s","scope":"%s","iat":%d,"nbf":%d,"exp":%d,"jti":"%s"}' \
    "${ISSUER}" "${AUDIENCE}" "${sub}" "${scope}" "${now}" "${now}" "$((now + ttl))" "${jti}" | b64url_encode)"
  signing_input="${header}.${payload}"
  signature="$(printf '%s' "${signing_input}" | openssl dgst -sha256 -sign "${PRIVATE_KEY}" | b64url_encode)"

  printf '%s.%s\n' "${signing_input}" "${signature}"
}

main "$@"
