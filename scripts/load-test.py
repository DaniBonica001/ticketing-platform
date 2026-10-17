#!/usr/bin/env python3
"""Concurrent purchase burst against the running local stack.

Creates an event with CAPACITY tickets, fires BUYERS concurrent orders (one distinct user each, so the
per-user rate limit does not interfere), waits until the processor has handled every order and then
asserts the inventory invariants: no overselling and counters that always add up to the capacity.

Standard library only. Tunable with environment variables:
  API_URL (default http://127.0.0.1:8090/api/v1), CAPACITY (50), BUYERS (500), MAX_QUANTITY (2),
  CONCURRENCY (100), DRAIN_TIMEOUT_SECONDS (120).
"""
from __future__ import annotations

import json
import os
import random
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

API_URL = os.environ.get("API_URL", "http://127.0.0.1:8090/api/v1")
CAPACITY = int(os.environ.get("CAPACITY", "50"))
BUYERS = int(os.environ.get("BUYERS", "500"))
MAX_QUANTITY = int(os.environ.get("MAX_QUANTITY", "2"))
CONCURRENCY = int(os.environ.get("CONCURRENCY", "100"))
DRAIN_TIMEOUT_SECONDS = int(os.environ.get("DRAIN_TIMEOUT_SECONDS", "120"))
ISSUE_TOKEN = Path(__file__).resolve().parent / "issue-token.sh"
TERMINAL_OR_RESERVED = {"RESERVED", "REJECTED"}


def issue_token(subject: str, role: str) -> str:
    return subprocess.run([str(ISSUE_TOKEN), subject, role], check=True, capture_output=True,
                          text=True).stdout.strip()


def call(method: str, path: str, token: str | None = None, body: dict | None = None,
         headers: dict | None = None) -> tuple[int, dict]:
    request = urllib.request.Request(f"{API_URL}{path}", method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def place_order(buyer: tuple[str, int], event_id: str) -> dict:
    token, quantity = buyer
    started = time.perf_counter()
    status, body = call("POST", "/orders", token, {"eventId": event_id, "quantity": quantity},
                        {"Idempotency-Key": str(uuid.uuid4())})
    return {"http": status, "orderId": body.get("orderId"), "quantity": quantity, "token": token,
            "latencyMs": (time.perf_counter() - started) * 1000}


def wait_until_processed(orders: list[dict]) -> dict[str, dict]:
    deadline = time.monotonic() + DRAIN_TIMEOUT_SECONDS
    final: dict[str, dict] = {}
    pending = [o for o in orders if o["orderId"]]
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        while pending and time.monotonic() < deadline:
            results = list(pool.map(lambda o: (o, call("GET", f"/orders/{o['orderId']}", o["token"])[1]),
                                    pending))
            for order, body in results:
                if body.get("status") in TERMINAL_OR_RESERVED:
                    final[order["orderId"]] = body
            pending = [o for o in pending if o["orderId"] not in final]
            if pending:
                time.sleep(1)
    if pending:
        sys.exit(f"FAIL: {len(pending)} orders still unprocessed after {DRAIN_TIMEOUT_SECONDS}s")
    return final


def percentile(values: list[float], pct: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(pct / 100 * (len(ordered) - 1))))]


def check(condition: bool, label: str) -> None:
    print(f"  {'OK  ' if condition else 'FAIL'} {label}")
    if not condition:
        sys.exit(1)


def main() -> None:
    random.seed(42)
    admin = issue_token("load-admin", "admin")
    status, event = call("POST", "/events", admin, {"name": "Load test", "venue": "Arena",
                                                     "eventDate": "2026-12-31T21:00:00-05:00",
                                                     "capacity": CAPACITY})
    if status != 201:
        sys.exit(f"FAIL: could not create the event ({status}): {event}")
    event_id = event["eventId"]
    buyers = [(issue_token(f"load-buyer-{i}", "user"), random.randint(1, MAX_QUANTITY)) for i in range(BUYERS)]
    demand = sum(quantity for _, quantity in buyers)
    print(f"Event {event_id}: capacity {CAPACITY}, {BUYERS} buyers, demand {demand} tickets, "
          f"concurrency {CONCURRENCY}")

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        orders = list(pool.map(lambda buyer: place_order(buyer, event_id), buyers))
    burst_seconds = time.perf_counter() - started
    accepted = [o for o in orders if o["http"] == 202]
    latencies = [o["latencyMs"] for o in accepted]
    print(f"\nBurst: {len(orders)} requests in {burst_seconds:.2f}s ({len(orders) / burst_seconds:.0f} req/s), "
          f"accepted {len(accepted)}, other {sorted({o['http'] for o in orders} - {202})}")
    print(f"  202 latency ms: p50 {statistics.median(latencies):.0f}, p95 {percentile(latencies, 95):.0f}, "
          f"p99 {percentile(latencies, 99):.0f}, max {max(latencies):.0f}")

    final = wait_until_processed(accepted)
    drain_seconds = time.perf_counter() - started
    reserved = [o for o in accepted if final[o["orderId"]]["status"] == "RESERVED"]
    rejected = [o for o in accepted if final[o["orderId"]]["status"] == "REJECTED"]
    reserved_tickets = sum(o["quantity"] for o in reserved)
    _, availability = call("GET", f"/events/{event_id}/availability")
    counters = ("available", "reserved", "pendingConfirmation", "sold", "complimentary")
    print(f"\nAll orders processed {drain_seconds:.1f}s after the burst started: "
          f"{len(reserved)} RESERVED ({reserved_tickets} tickets), {len(rejected)} REJECTED")
    print(f"  availability: { {name: availability[name] for name in counters} }")

    print("\nInvariants")
    check(len(accepted) == BUYERS, "every request was accepted (202)")
    check(reserved_tickets <= CAPACITY, f"no overselling: {reserved_tickets} reserved <= capacity {CAPACITY}")
    check(availability["reserved"] == reserved_tickets, "inventory 'reserved' equals the sum of RESERVED orders")
    check(sum(availability[name] for name in counters) == CAPACITY, "counters add up to the capacity")
    check(all(final[o["orderId"]].get("reason") == "SOLD_OUT" for o in rejected), "every rejection is SOLD_OUT")
    if demand >= CAPACITY + MAX_QUANTITY:
        check(availability["available"] < MAX_QUANTITY, "inventory exhausted (no ticket left unsold by mistake)")
    print("\nLoad test passed.")


if __name__ == "__main__":
    main()
