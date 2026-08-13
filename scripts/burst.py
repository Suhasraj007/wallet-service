#!/usr/bin/env python3
"""One-command concurrency probe for the wallet service. Stdlib only.

What it does, against a live base URL:
  1. Creates two brand-new users (fresh ids every run) and mints tokens.
  2. Fires ONE combined concurrent wave, released by a barrier:
       - N first-transfers with distinct idempotency keys, and
       - M retries of a single shared idempotency key,
     all racing the get-or-create of both wallets at the same time.
  3. Reconciles to the paisa via the API and prints PASS/FAIL:
       - every distinct-key transfer applied exactly once (all 201)
       - all M same-key retries returned the identical original outcome
       - sender == seed - N*amount - retry_amount, receiver mirrors it,
         so the total is conserved exactly
       - zero 5xx / transport errors anywhere
  4. Replays the shared key with a different body and expects 409.

Usage:
  python3 scripts/burst.py https://your-app.example.com
  python3 scripts/burst.py http://localhost:8080 --transfers 50 --retries 50
"""
import argparse
import json
import random
import ssl
import string
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor


def _tls_context():
    """Verified TLS, with a working trust store on every machine.

    Python builds from python.org on macOS ship without the system CA
    bundle wired up, so urllib rejects every certificate until the bundled
    "Install Certificates.command" is run. certifi, when present, provides
    the same roots. Verification is never disabled - a probe that skips it
    is not a probe you can trust.
    """
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


TLS = _tls_context()

CERT_HINT = (
    "TLS certificate verification failed. This is a local Python trust-store "
    "problem, not a problem with the service.\n"
    "  macOS (python.org build): run \"/Applications/Python 3.x/Install "
    "Certificates.command\"\n"
    "  or install the roots: python3 -m pip install --upgrade certifi"
)


def call(base, method, path, body=None, token=None, request_id=None, timeout=30):
    url = base.rstrip("/") + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if request_id:
        req.add_header("X-Request-Id", request_id)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=TLS) as resp:
            payload = json.loads(resp.read().decode() or "{}")
            return resp.status, payload, time.monotonic() - started
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode() or "{}")
        except Exception:
            payload = {}
        return e.code, payload, time.monotonic() - started
    except urllib.error.URLError as e:
        reason = e.reason
        if isinstance(reason, ssl.SSLCertVerificationError):
            return 0, {"error": "tls", "message": str(reason)}, time.monotonic() - started
        return 0, {"error": "transport", "message": str(reason)}, time.monotonic() - started
    except Exception as e:
        return 0, {"error": "transport", "message": str(e)}, time.monotonic() - started


def wait_until_awake(base, attempts=20, delay=5):
    """Free-tier hosts may sleep; poke /healthz until the instance is up.

    The per-attempt timeout is generous because a cold start on a free
    instance can take the better part of a minute, and a probe that gives up
    early would report a waking service as a dead one.
    """
    for attempt in range(1, attempts + 1):
        status, body, _ = call(base, "GET", "/healthz", timeout=30)
        if status == 200:
            return True
        if body.get("error") == "tls":
            print("\nFAIL: " + CERT_HINT)
            print(f"  detail: {body.get('message')}")
            sys.exit(1)
        detail = body.get("message") or "no response"
        print(f"  waiting for the service to wake up "
              f"(attempt {attempt}/{attempts}, got {status or detail})...")
        time.sleep(delay)
    return False


def main():
    parser = argparse.ArgumentParser(description="Wallet service concurrency probe")
    parser.add_argument("base_url", help="e.g. https://wallet.example.com")
    parser.add_argument("--transfers", type=int, default=30,
                        help="concurrent first-transfers with distinct keys (default 30)")
    parser.add_argument("--retries", type=int, default=30,
                        help="concurrent retries of ONE shared key (default 30)")
    parser.add_argument("--amount", type=int, default=500,
                        help="paise per distinct-key transfer (default 500)")
    parser.add_argument("--retry-amount", type=int, default=700,
                        help="paise for the shared-key transfer (default 700)")
    args = parser.parse_args()
    base = args.base_url

    run_id = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
    sender = f"burst-{run_id}-a"
    receiver = f"burst-{run_id}-b"
    probe_user = f"burst-{run_id}-seedprobe"

    checks = []

    def check(name, ok, detail=""):
        checks.append((name, ok))
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))

    print(f"== wallet burst probe ==  target: {base}  run: {run_id}")

    if not wait_until_awake(base):
        print("FAIL: service did not become healthy; aborting.")
        sys.exit(1)
    status, _, _ = call(base, "GET", "/readyz")
    check("readyz reports ready", status == 200, f"status={status}")

    # Discover the configured seed balance from a throwaway account. The two
    # probe users are NOT created here - the transfer wave itself must
    # get-or-create them under the race.
    status, body, _ = call(base, "POST", "/auth/token", {"user_id": probe_user})
    if status != 200:
        print(f"FAIL: could not mint token ({status} {body}); aborting.")
        sys.exit(1)
    status, body, _ = call(base, "POST", "/accounts", {}, token=body["token"])
    seed = int(body.get("balance_paise", -1)) if status == 200 else -1
    check("seed balance discovered", seed >= 0, f"seed={seed} paise")
    if seed < 0:
        print("FAIL: could not read a seed balance; aborting.")
        sys.exit(1)

    def mint(user):
        status, body, _ = call(base, "POST", "/auth/token", {"user_id": user})
        if status != 200 or "token" not in body:
            print(f"FAIL: could not mint a token for {user} ({status} {body}); aborting.")
            sys.exit(1)
        return body["token"]

    sender_token = mint(sender)
    receiver_token = mint(receiver)

    n, m = args.transfers, args.retries
    amount, retry_amount = args.amount, args.retry_amount
    shared_key = f"burst-{run_id}-shared"

    # Every transfer in the wave must be fundable, otherwise the run would
    # report a failure for what is really a badly chosen set of arguments.
    required = n * amount + retry_amount
    if required > seed:
        print(f"FAIL: this run needs {required} paise but a new wallet holds "
              f"{seed}. Lower --transfers/--amount, or raise "
              f"INITIAL_BALANCE_PAISE on the service.")
        sys.exit(1)

    jobs = [("distinct", f"burst-{run_id}-k{i}", amount) for i in range(n)]
    jobs += [("retry", shared_key, retry_amount)] * m
    random.shuffle(jobs)
    barrier = threading.Barrier(len(jobs))
    results = [None] * len(jobs)

    def fire(index, kind, key, amt):
        barrier.wait()
        status, body, elapsed = call(
            base, "POST", "/transfers",
            {"to_user": receiver, "amount_paise": amt, "idempotency_key": key},
            token=sender_token, request_id=f"burst-{run_id}-{index}")
        results[index] = (kind, status, body, elapsed)

    print(f"  firing {n} distinct-key + {m} same-key transfers in one wave...")
    wave_started = time.monotonic()
    with ThreadPoolExecutor(max_workers=len(jobs)) as pool:
        for index, (kind, key, amt) in enumerate(jobs):
            pool.submit(fire, index, kind, key, amt)
    wave_seconds = time.monotonic() - wave_started

    distinct = [r for r in results if r[0] == "distinct"]
    retried = [r for r in results if r[0] == "retry"]
    server_errors = [r for r in results if r[1] >= 500 or r[1] == 0]
    latencies = sorted(r[3] for r in results)
    p50 = latencies[len(latencies) // 2]
    p95 = latencies[int(len(latencies) * 0.95) - 1]
    print(f"  wave done in {wave_seconds:.2f}s  "
          f"(p50 {p50 * 1000:.0f}ms, p95 {p95 * 1000:.0f}ms per request)")

    check("zero 5xx / transport errors", len(server_errors) == 0,
          f"count={len(server_errors)} sample={[(r[1], r[2]) for r in server_errors[:3]]}")
    check(f"all {n} distinct-key transfers applied (201)",
          all(r[1] == 201 for r in distinct),
          f"statuses={sorted(set(r[1] for r in distinct))}")

    retry_ids = {r[2].get("transfer_id") for r in retried}
    retry_balances = {r[2].get("new_balance_paise") for r in retried}
    check(f"all {m} same-key retries returned 201",
          all(r[1] == 201 for r in retried),
          f"statuses={sorted(set(r[1] for r in retried))}")
    check("same-key retries all returned ONE transfer_id",
          len(retry_ids) == 1, f"distinct ids={len(retry_ids)}")
    check("same-key retries all returned the identical balance",
          len(retry_balances) == 1)

    _, sender_acct, _ = call(base, "GET", "/accounts/me", token=sender_token)
    _, receiver_acct, _ = call(base, "GET", "/accounts/me", token=receiver_token)
    sender_balance = int(sender_acct.get("balance_paise", -1))
    receiver_balance = int(receiver_acct.get("balance_paise", -1))
    expected_moved = n * amount + retry_amount
    check("sender balance exact: seed - moved",
          sender_balance == seed - expected_moved,
          f"expected {seed - expected_moved}, got {sender_balance}")
    check("receiver balance exact: seed + moved",
          receiver_balance == seed + expected_moved,
          f"expected {seed + expected_moved}, got {receiver_balance}")
    check("conservation: sender + receiver == 2 * seed",
          sender_balance + receiver_balance == 2 * seed)

    status, body, _ = call(
        base, "POST", "/transfers",
        {"to_user": receiver, "amount_paise": retry_amount + 1,
         "idempotency_key": shared_key},
        token=sender_token)
    check("same key + different body rejected with 409", status == 409,
          f"status={status}")

    failed = [name for name, ok in checks if not ok]
    print("=" * 60)
    if failed:
        print(f"RESULT: FAIL ({len(failed)}/{len(checks)} checks failed): {failed}")
        sys.exit(1)
    print(f"RESULT: PASS ({len(checks)}/{len(checks)} checks) - money conserved, "
          f"idempotency held, no 5xx.")


if __name__ == "__main__":
    main()