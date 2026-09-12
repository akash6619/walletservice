#!/usr/bin/env python3
"""Generate short-lived demo JWTs locally; never persist or commit the output."""

import argparse
import base64
import hashlib
import hmac
import json
import os
import time


USERS = {
    "TOKEN_A": "00000000-0000-0000-0000-000000000001",
    "TOKEN_B": "00000000-0000-0000-0000-000000000002",
}


def encode(value: object) -> str:
    raw = json.dumps(value, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def token(secret: str, subject: str, lifetime_seconds: int) -> str:
    now = int(time.time())
    message = ".".join((
        encode({"alg": "HS256", "typ": "JWT"}),
        encode({
            "sub": subject,
            "iss": "wallet-service-demo",
            "aud": "wallet-service",
            "iat": now,
            "exp": now + lifetime_seconds,
        }),
    ))
    signature = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), message.encode(), hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    return f"{message}.{signature}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lifetime-seconds", type=int, default=3600)
    args = parser.parse_args()
    secret = os.environ.get("JWT_SECRET", "")
    if len(secret.encode()) < 32:
        parser.error("JWT_SECRET must contain at least 32 bytes")
    if args.lifetime_seconds <= 0:
        parser.error("--lifetime-seconds must be positive")
    for name, subject in USERS.items():
        print(f"export {name}='{token(secret, subject, args.lifetime_seconds)}'")


if __name__ == "__main__":
    main()

