#!/usr/bin/env bash
# End-to-end demo of the main flows against the local stack (make up).
# Ana buys and pays, Beto reserves, Carla gets SOLD_OUT, retries are idempotent,
# a payment is declined, an admin issues a complimentary ticket and optimistic locking is exercised.
# Every step asserts the expected result and the script exits non-zero on the first mismatch.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_URL="${API_URL:-http://127.0.0.1:8090/api/v1}"
ASYNC_WAIT_SECONDS="${ASYNC_WAIT_SECONDS:-3}"

token() { "${SCRIPT_DIR}/issue-token.sh" "$1" "$2"; }
json() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
expect() {
  local actual="$1" expected="$2" label="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    printf '  \033[31mFAIL\033[0m %s: expected %s, got %s\n' "${label}" "${expected}" "${actual}" >&2
    exit 1
  fi
  printf '  \033[32mOK\033[0m   %s = %s\n' "${label}" "${actual}"
}

post() { # token path body [extra curl args...]
  local auth="$1" path="$2" body="$3"; shift 3
  curl -sS -X POST "${API_URL}${path}" -H "Authorization: Bearer ${auth}" \
    -H 'Content-Type: application/json' "$@" -d "${body}"
}
get() { curl -sS "${API_URL}$1" ${2:+-H "Authorization: Bearer $2"}; }
place_order() { # token idempotencyKey eventId quantity
  post "$1" /orders "{\"eventId\":\"$3\",\"quantity\":$4}" -H "Idempotency-Key: $2"
}
status_of() { get "/orders/$1" "$2" | json "['status']"; }

ADMIN="$(token admin-1 admin)"
ANA="$(token ana user)"
BETO="$(token beto user)"
CARLA="$(token carla user)"

step "0. Admin creates an event with capacity 5"
EVENT="$(post "${ADMIN}" /events '{"name":"Karol G - Tropicoqueta Tour","eventDate":"2026-12-20T20:00:00-05:00","venue":"Estadio Atanasio Girardot","capacity":5}')"
EVENT_ID="$(echo "${EVENT}" | json "['eventId']")"
expect "$(echo "${EVENT}" | json "['availability']['available']")" 5 "available"

step "1. Ana orders 2 tickets (202 + QUEUED)"
ANA_KEY="$(uuidgen)"
ANA_ORDER="$(place_order "${ANA}" "${ANA_KEY}" "${EVENT_ID}" 2)"
ANA_ORDER_ID="$(echo "${ANA_ORDER}" | json "['orderId']")"
expect "$(echo "${ANA_ORDER}" | json "['status']")" QUEUED "status"

step "1b. Ana's phone retries with the same Idempotency-Key"
expect "$(place_order "${ANA}" "${ANA_KEY}" "${EVENT_ID}" 2 | json "['orderId']")" "${ANA_ORDER_ID}" "same orderId"
expect "$(place_order "${ANA}" "${ANA_KEY}" "${EVENT_ID}" 3 | json "['code']")" IDEMPOTENCY_KEY_REUSED "same key, other payload"

sleep "${ASYNC_WAIT_SECONDS}"
step "2. The processor reserved Ana's tickets"
expect "$(status_of "${ANA_ORDER_ID}" "${ANA}")" RESERVED "Ana"

step "3. Beto orders 2 tickets"
BETO_ORDER_ID="$(place_order "${BETO}" "$(uuidgen)" "${EVENT_ID}" 2 | json "['orderId']")"
sleep "${ASYNC_WAIT_SECONDS}"
expect "$(status_of "${BETO_ORDER_ID}" "${BETO}")" RESERVED "Beto"

step "4. Carla orders 2 tickets but only 1 is left"
CARLA_ORDER_ID="$(place_order "${CARLA}" "$(uuidgen)" "${EVENT_ID}" 2 | json "['orderId']")"
sleep "${ASYNC_WAIT_SECONDS}"
CARLA_ORDER="$(get "/orders/${CARLA_ORDER_ID}" "${CARLA}")"
expect "$(echo "${CARLA_ORDER}" | json "['status']")" REJECTED "Carla"
expect "$(echo "${CARLA_ORDER}" | json "['reason']")" SOLD_OUT "reason"

step "4b. Beto cannot read Ana's order"
expect "$(curl -s -o /dev/null -w '%{http_code}' "${API_URL}/orders/${ANA_ORDER_ID}" -H "Authorization: Bearer ${BETO}")" 404 "HTTP"

step "5. Ana confirms the payment"
expect "$(post "${ANA}" "/orders/${ANA_ORDER_ID}/confirm" '{"paymentReference":"PAY-123"}' | json "['status']")" \
  PENDING_CONFIRMATION "status"
sleep "${ASYNC_WAIT_SECONDS}"
step "6. The processor approved the payment"
expect "$(status_of "${ANA_ORDER_ID}" "${ANA}")" SOLD "Ana"

step "7. Admin issues 1 complimentary ticket"
expect "$(post "${ADMIN}" "/events/${EVENT_ID}/complimentary" '{"quantity":1,"recipientUserId":"vip-1"}' \
  -H "Idempotency-Key: $(uuidgen)" | json "['status']")" COMPLIMENTARY "status"

step "8. Availability (sum == capacity)"
AVAILABILITY="$(get "/events/${EVENT_ID}/availability")"
echo "  ${AVAILABILITY}"
expect "$(echo "${AVAILABILITY}" | json "['sold']")" 2 "sold"
expect "$(echo "${AVAILABILITY}" | json "['reserved']")" 2 "reserved"
expect "$(echo "${AVAILABILITY}" | json "['complimentary']")" 1 "complimentary"
expect "$(echo "${AVAILABILITY}" | json "['available']")" 0 "available"

step "9. Audit trail of Ana's order"
get "/orders/${ANA_ORDER_ID}/history" "${ANA}" | python3 -c "
import sys, json
for t in json.load(sys.stdin):
    print(f\"  {t.get('fromStatus') or '-':22s} -> {t['toStatus']:22s} {t['actor']:16s} {t['occurredAt']}\")"

step "10. Payment declined returns the ticket to the inventory"
THEATER_ID="$(post "${ADMIN}" /events '{"name":"Teatro","eventDate":"2026-12-01T19:00:00-05:00","venue":"Teatro Pablo Tobon","capacity":3}' | json "['eventId']")"
DANI="$(token dani user)"
DANI_ORDER_ID="$(place_order "${DANI}" "$(uuidgen)" "${THEATER_ID}" 1 | json "['orderId']")"
sleep "${ASYNC_WAIT_SECONDS}"
post "${DANI}" "/orders/${DANI_ORDER_ID}/confirm" '{"paymentReference":"DECLINED-001"}' >/dev/null
sleep "${ASYNC_WAIT_SECONDS}"
expect "$(get "/orders/${DANI_ORDER_ID}" "${DANI}" | json "['reason']")" PAYMENT_DECLINED "reason"
expect "$(get "/events/${THEATER_ID}/availability" | json "['available']")" 3 "available"

step "11. Optimistic locking on event updates"
UPDATE='{"name":"Teatro (new date)","eventDate":"2026-12-02T19:00:00-05:00","venue":"Teatro Pablo Tobon"}'
http_put() { curl -s -o /dev/null -w '%{http_code}' -X PUT "${API_URL}/events/${THEATER_ID}" \
  -H "Authorization: Bearer ${ADMIN}" -H 'Content-Type: application/json' "$@" -d "${UPDATE}"; }
ETAG="$(curl -s -D - -o /dev/null "${API_URL}/events/${THEATER_ID}" | awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')"
expect "$(http_put)" 428 "without If-Match"
expect "$(http_put -H 'If-Match: "999"')" 412 "stale If-Match"
expect "$(http_put -H "If-Match: ${ETAG}")" 200 "current If-Match"

printf '\n\033[32mAll flows passed.\033[0m Beto'"'"'s reservation (%s) expires in 10 minutes and the\n' "${BETO_ORDER_ID}"
printf 'expirer Lambda will move it to EXPIRED and return 2 tickets to the inventory:\n'
printf '  curl -s %s/orders/%s -H "Authorization: Bearer $(scripts/issue-token.sh beto user)"\n' "${API_URL}" "${BETO_ORDER_ID}"
