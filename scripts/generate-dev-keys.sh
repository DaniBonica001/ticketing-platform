#!/usr/bin/env bash
# Generates the RSA 2048 key pair of the simulated local IdP (ADR-0006).
#   local/secrets/jwt-private.pem  signs dev tokens (scripts/issue-token.sh); never leaves the host
#   local/secrets/jwt-public.pem   mounted into ms-ticketing-api as /run/secrets/jwt-public.pem
# Idempotent: existing keys are kept. Pass --force to rotate them.
# The directory is git-ignored; keys are never committed.
set -euo pipefail

readonly RSA_BITS=2048
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT
readonly SECRETS_DIR="${REPO_ROOT}/local/secrets"
readonly PRIVATE_KEY="${SECRETS_DIR}/jwt-private.pem"
readonly PUBLIC_KEY="${SECRETS_DIR}/jwt-public.pem"

usage() {
  printf 'Usage: %s [--force]\n' "$(basename "$0")" >&2
  exit 2
}

derive_public_key() {
  openssl rsa -in "${PRIVATE_KEY}" -pubout -out "${PUBLIC_KEY}" 2>/dev/null
  chmod 0644 "${PUBLIC_KEY}"
}

main() {
  local force=false
  case "${1:-}" in
    "") ;;
    --force) force=true ;;
    *) usage ;;
  esac

  command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
  mkdir -p "${SECRETS_DIR}"
  chmod 0700 "${SECRETS_DIR}"

  if [[ "${force}" == false && -s "${PRIVATE_KEY}" ]]; then
    if [[ ! -s "${PUBLIC_KEY}" ]]; then
      derive_public_key
      echo "Re-derived ${PUBLIC_KEY} from the existing private key."
    else
      echo "Keys already exist in ${SECRETS_DIR} (use --force to rotate)."
    fi
    return 0
  fi

  # Write to a temp file first so an interrupted run never leaves a truncated key behind.
  local tmp_key
  tmp_key="$(mktemp "${SECRETS_DIR}/.jwt-private.XXXXXX")"
  trap 'rm -f "${tmp_key}"' EXIT
  (umask 077 && openssl genpkey -algorithm RSA -pkeyopt "rsa_keygen_bits:${RSA_BITS}" -out "${tmp_key}" 2>/dev/null)
  chmod 0600 "${tmp_key}"
  mv -f "${tmp_key}" "${PRIVATE_KEY}"
  trap - EXIT
  derive_public_key

  echo "Generated RSA ${RSA_BITS} key pair:"
  echo "  private: ${PRIVATE_KEY}"
  echo "  public:  ${PUBLIC_KEY}"
}

main "$@"
