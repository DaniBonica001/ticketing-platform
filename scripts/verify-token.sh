#!/usr/bin/env bash
# Verifies the RS256 signature of a JWT against the local public key and prints its claims.
# Checks the signature and expiry only; the API also validates iss, aud and nbf.
#
#   scripts/verify-token.sh <token>      or      scripts/issue-token.sh u1 user | scripts/verify-token.sh
#
# Environment override: JWT_PUBLIC_KEY_FILE.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
# shellcheck source-path=SCRIPTDIR
source "${SCRIPT_DIR}/lib/jwt.sh"

readonly PUBLIC_KEY="${JWT_PUBLIC_KEY_FILE:-${SCRIPT_DIR}/../local/secrets/jwt-public.pem}"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

main() {
  local token="${1:-}"
  [[ -n "${token}" ]] || IFS= read -r token
  [[ "${token}" =~ ^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]] || die "not a compact JWS"
  [[ -r "${PUBLIC_KEY}" ]] || die "public key not found at ${PUBLIC_KEY}"

  local header payload signature
  IFS=. read -r header payload signature <<<"${token}"

  # Global (not local) so the EXIT trap can still see it after main returns.
  WORKDIR="$(mktemp -d)"
  trap 'rm -rf "${WORKDIR}"' EXIT
  b64url_decode "${signature}" >"${WORKDIR}/sig"

  if ! printf '%s.%s' "${header}" "${payload}" |
    openssl dgst -sha256 -verify "${PUBLIC_KEY}" -signature "${WORKDIR}/sig" >/dev/null; then
    die "signature verification FAILED"
  fi

  local claims exp
  claims="$(b64url_decode "${payload}")"
  exp="$(printf '%s' "${claims}" | sed -n 's/.*"exp":\([0-9]*\).*/\1/p')"
  echo "signature: OK"
  echo "header:    $(b64url_decode "${header}")"
  echo "claims:    ${claims}"
  if [[ -n "${exp}" ]] && ((exp <= $(date +%s))); then
    die "token is expired"
  fi
}

main "$@"
