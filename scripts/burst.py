#!/usr/bin/env python3
"""Exercise concurrent transfers and verify idempotency and balance conservation."""

from __future__ import annotations

import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass


USER_A = "00000000-0000-0000-0000-000000000001"
USER_B = "00000000-0000-0000-0000-000000000002"
LOCAL_URLS = {"http://localhost:8080", "http://127.0.0.1:8080"}


@dataclass(frozen=True)
class Response:
    status: int
    body: dict[str, object]


def positive_int(name: str, default: int) -> int:
    raw = os.environ.get(name, str(default))
    try:
        value = int(raw)
    except ValueError:
        raise SystemExit(f"FAIL {name} must be an integer, got {raw!r}") from None
    if value <= 0:
        raise SystemExit(f"FAIL {name} must be positive, got {value}")
    return value


def encode_jwt_part(value: object) -> str:
    raw = json.dumps(value, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def make_token(secret: str, subject: str) -> str:
    now = int(time.time())
    header = encode_jwt_part({"alg": "HS256", "typ": "JWT"})
    payload = encode_jwt_part({
        "sub": subject,
        "iss": "wallet-service-demo",
        "aud": "wallet-service",
        "iat": now,
        "exp": now + 3600,
    })
    message = f"{header}.{payload}"
    signature = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), message.encode(), hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    return f"{message}.{signature}"


def subject(token: str) -> str:
    try:
        encoded = token.split(".")[1]
        encoded += "=" * (-len(encoded) % 4)
        value = json.loads(base64.urlsafe_b64decode(encoded))
        return str(uuid.UUID(value["sub"]))
    except (IndexError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(f"FAIL token has no valid UUID subject: {error}") from None


def request(base_url: str, method: str, path: str, token: str, body: dict[str, object] | None = None) -> Response:
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Authorization": f"Bearer {token}"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"{base_url}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            raw = response.read()
            return Response(response.status, json.loads(raw) if raw else {})
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw.decode(errors="replace")}
        return Response(error.code, parsed)
    except urllib.error.URLError as error:
        raise RuntimeError(f"request to {path} failed: {error.reason}") from error


def create_account(base_url: str, token: str) -> int:
    response = request(base_url, "POST", "/accounts", token)
    if response.status != 200:
        raise RuntimeError(f"account setup returned {response.status}: {response.body}")
    return int(response.body["balance"])


def transfer_with_retry(
    base_url: str,
    token: str,
    recipient: str,
    amount: int,
    key: str,
    max_retries: int,
) -> Response:
    body = {"to_user": recipient, "amount_paise": amount, "idempotency_key": key}
    for attempt in range(1, max_retries + 1):
        response = request(base_url, "POST", "/transfers", token, body)
        if response.status == 500:
            raise RuntimeError(f"key={key} returned 500: {response.body}")
        if response.status != 503:
            return response
        if attempt < max_retries:
            time.sleep(attempt)
    raise RuntimeError(f"key={key} remained unavailable after {max_retries} attempts")


def tokens_for_target(base_url: str) -> tuple[str, str]:
    if base_url not in LOCAL_URLS:
        token_a, token_b = os.environ.get("TOKEN_A"), os.environ.get("TOKEN_B")
        if not token_a or not token_b:
            raise SystemExit(f"FAIL TOKEN_A and TOKEN_B are required for non-local BASE_URL={base_url}")
        return token_a, token_b

    # Local mode is intentionally zero-config. Ignore exported deployment tokens,
    # which are signed with a different secret and would produce confusing 401s.
    sql = (
        "INSERT INTO users (user_id, name) VALUES "
        f"('{USER_A}', 'Demo User A'), ('{USER_B}', 'Demo User B') "
        "ON CONFLICT (user_id) DO NOTHING"
    )
    try:
        subprocess.run(
            ["docker", "compose", "exec", "-T", "db", "psql", "-v", "ON_ERROR_STOP=1", "-U", "wallet", "-d", "wallet", "-c", sql],
            check=True,
            stdout=subprocess.DEVNULL,
        )
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"FAIL could not seed local Compose database: {error}") from None

    secret = os.environ.get("LOCAL_JWT_SECRET", "local-development-secret-change-before-deploy")
    print("Using local Compose demo users and short-lived JWTs.")
    return make_token(secret, USER_A), make_token(secret, USER_B)


def main() -> None:
    base_url = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
    concurrency = positive_int("CONCURRENCY", 20)
    amount = positive_int("AMOUNT_PAISE", 1)
    max_retries = positive_int("MAX_RETRIES", 10)
    token_a, token_b = tokens_for_target(base_url)
    user_a, user_b = subject(token_a), subject(token_b)

    try:
        start_a = create_account(base_url, token_a)
        start_b = create_account(base_url, token_b)
        run_id = uuid.uuid4()
        duplicate_key = f"burst-duplicate-{run_id}"
        jobs: list[tuple[str, concurrent.futures.Future[Response]]] = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency * 2) as executor:
            for index in range(concurrency):
                key = f"burst-a-{run_id}-{index}"
                jobs.append(("distinct", executor.submit(
                    transfer_with_retry, base_url, token_a, user_b, amount, key, max_retries
                )))
            for _ in range(concurrency):
                jobs.append(("duplicate", executor.submit(
                    transfer_with_retry, base_url, token_b, user_a, amount, duplicate_key, max_retries
                )))
        results = [(kind, future.result()) for kind, future in jobs]
    except (KeyError, TypeError, ValueError, RuntimeError) as error:
        raise SystemExit(f"FAIL {error}") from None

    statuses = [response.status for _, response in results]
    if not set(statuses) <= {200, 422}:
        raise SystemExit(f"FAIL unexpected transfer statuses: {statuses}")

    distinct = [response for kind, response in results if kind == "distinct"]
    duplicates = [response for kind, response in results if kind == "duplicate"]
    duplicate_statuses = {response.status for response in duplicates}
    if len(duplicate_statuses) != 1:
        raise SystemExit(f"FAIL identical-key requests returned inconsistent statuses: {sorted(duplicate_statuses)}")

    successful_duplicates = [response.body for response in duplicates if response.status == 200]
    if successful_duplicates:
        transfer_ids = {body["transfer_id"] for body in successful_duplicates}
        balances = {body["new_balance"] for body in successful_duplicates}
        if len(transfer_ids) != 1:
            raise SystemExit(f"FAIL duplicate key returned multiple transfer IDs: {transfer_ids}")
        if len(balances) != 1:
            raise SystemExit(f"FAIL duplicate key returned inconsistent balances: {balances}")
        duplicate_applied = 1
    else:
        duplicate_applied = 0

    successful_distinct = sum(response.status == 200 for response in distinct)
    if successful_distinct + duplicate_applied == 0:
        raise SystemExit("FAIL no transfer was applied; balance conservation was not meaningfully exercised")

    expected_a = start_a - successful_distinct * amount + duplicate_applied * amount
    expected_b = start_b + successful_distinct * amount - duplicate_applied * amount
    try:
        final_a = int(request(base_url, "GET", "/accounts/me", token_a).body["balance"])
        final_b = int(request(base_url, "GET", "/accounts/me", token_b).body["balance"])
    except (KeyError, TypeError, ValueError, RuntimeError) as error:
        raise SystemExit(f"FAIL final balance read failed: {error}") from None
    if (final_a, final_b) != (expected_a, expected_b) or final_a + final_b != start_a + start_b:
        raise SystemExit(
            f"FAIL balances: start=({start_a},{start_b}) expected=({expected_a},{expected_b}) "
            f"actual=({final_a},{final_b})"
        )
    print(
        f"PASS {concurrency} distinct requests plus {concurrency} identical-key retries; "
        f"balances reconciled ({final_a} + {final_b} = {final_a + final_b})"
    )


if __name__ == "__main__":
    main()
