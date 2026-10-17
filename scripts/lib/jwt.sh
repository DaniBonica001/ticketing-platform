#!/usr/bin/env bash
# base64url helpers shared by issue-token.sh and verify-token.sh. Sourced, not executed.

b64url_encode() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

b64url_decode() {
  local data="$1"
  local padding=$(((4 - ${#data} % 4) % 4))
  data="${data//-/+}"
  data="${data//_//}"
  while ((padding-- > 0)); do data+="="; done
  printf '%s' "${data}" | openssl base64 -d -A
}
