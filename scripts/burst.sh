#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-20}"
AMOUNT_PAISE="${AMOUNT_PAISE:-1}"
MAX_RETRIES="${MAX_RETRIES:-5}"
BASE_URL="${BASE_URL%/}"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
run_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"

make_local_token() {
  python3 -c '
import base64, hashlib, hmac, json, sys, time
secret, subject = sys.argv[1:3]
encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()
header = encode({"alg": "HS256", "typ": "JWT"})
payload = encode({"sub": subject, "iss": "wallet-service-demo", "aud": "wallet-service", "iat": int(time.time()), "exp": int(time.time()) + 3600})
message = f"{header}.{payload}"
signature = base64.urlsafe_b64encode(hmac.new(secret.encode(), message.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
print(f"{message}.{signature}")
' "$1" "$2"
}

if [[ -z "${TOKEN_A:-}" || -z "${TOKEN_B:-}" ]]; then
  if [[ "$BASE_URL" != "http://localhost:8080" && "$BASE_URL" != "http://127.0.0.1:8080" ]]; then
    printf 'FAIL TOKEN_A and TOKEN_B are required for non-local BASE_URL=%s\n' "$BASE_URL" >&2
    exit 1
  fi

  user_a="00000000-0000-0000-0000-000000000001"
  user_b="00000000-0000-0000-0000-000000000002"
  docker compose exec -T db psql -v ON_ERROR_STOP=1 -U wallet -d wallet -c \
    "INSERT INTO users (user_id, name) VALUES ('$user_a', 'Demo User A'), ('$user_b', 'Demo User B') ON CONFLICT (user_id) DO NOTHING" \
    >/dev/null
  local_secret="${LOCAL_JWT_SECRET:-local-development-secret-change-before-deploy}"
  TOKEN_A="$(make_local_token "$local_secret" "$user_a")"
  TOKEN_B="$(make_local_token "$local_secret" "$user_b")"
  printf 'Using local Compose demo users and short-lived JWTs.\n'
fi

json_number() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"
}

request() {
  local method="$1" token="$2" path="$3" body="${4:-}" output="$5"
  local args=(-sS -o "$output" -w '%{http_code}' -X "$method" -H "Authorization: Bearer ${token}")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  curl "${args[@]}" "${BASE_URL}${path}"
}

account() {
  local token="$1" output="$2" status
  status="$(request POST "$token" /accounts '' "$output")"
  [[ "$status" == 200 ]] || { printf 'FAIL account setup returned %s: %s\n' "$status" "$(<"$output")"; return 1; }
}

transfer_with_retry() {
  local token="$1" recipient="$2" amount="$3" key="$4" output="$5"
  local attempt=1 status body
  body="$(printf '{"to_user":"%s","amount_paise":%s,"idempotency_key":"%s"}' "$recipient" "$amount" "$key")"
  while (( attempt <= MAX_RETRIES )); do
    status="$(request POST "$token" /transfers "$body" "$output")"
    if [[ "$status" == 500 ]]; then
      printf 'FAIL key=%s returned 500: %s\n' "$key" "$(<"$output")" >&2
      return 1
    fi
    if [[ "$status" != 503 ]]; then
      printf '%s' "$status"
      return 0
    fi
    sleep "$attempt"
    ((attempt++))
  done
  printf 'FAIL key=%s remained unavailable after %s attempts\n' "$key" "$MAX_RETRIES" >&2
  return 1
}

account "$TOKEN_A" "$work_dir/account-a.json"
account "$TOKEN_B" "$work_dir/account-b.json"
start_a="$(json_number balance < "$work_dir/account-a.json")"
start_b="$(json_number balance < "$work_dir/account-b.json")"

# Tokens must have UUID subjects. The authenticated account endpoint intentionally
# does not expose them, so decode only the already-supplied JWT payload locally.
subject() {
  python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; p += "="*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p))["sub"])' "$1"
}
user_a="$(subject "$TOKEN_A")"
user_b="$(subject "$TOKEN_B")"

pids=()
for ((index=0; index<CONCURRENCY; index++)); do
  (
    key="burst-a-${run_id}-${index}"
    status="$(transfer_with_retry "$TOKEN_A" "$user_b" "$AMOUNT_PAISE" "$key" "$work_dir/a-${index}.json")"
    printf '%s' "$status" > "$work_dir/a-${index}.status"
  ) & pids+=("$!")
done

duplicate_key="burst-duplicate-${run_id}"
for ((index=0; index<CONCURRENCY; index++)); do
  (
    status="$(transfer_with_retry "$TOKEN_B" "$user_a" "$AMOUNT_PAISE" "$duplicate_key" "$work_dir/d-${index}.json")"
    printf '%s' "$status" > "$work_dir/d-${index}.status"
  ) & pids+=("$!")
done

failed=0
for pid in "${pids[@]}"; do
  wait "$pid" || failed=1
done
(( failed == 0 )) || { printf 'FAIL one or more requests failed\n'; exit 1; }

python3 - "$work_dir" "$CONCURRENCY" "$AMOUNT_PAISE" "$start_a" "$start_b" <<'PY'
import json, pathlib, sys

root = pathlib.Path(sys.argv[1])
concurrency, amount, start_a, start_b = map(int, sys.argv[2:])
statuses_a = [(root / f"a-{i}.status").read_text() for i in range(concurrency)]
statuses_d = [(root / f"d-{i}.status").read_text() for i in range(concurrency)]
allowed = {"200", "422"}
if not set(statuses_a + statuses_d) <= allowed:
    raise SystemExit(f"FAIL unexpected transfer statuses: {statuses_a + statuses_d}")

successful_a = sum(status == "200" for status in statuses_a)
duplicate_statuses = set(statuses_d)
if len(duplicate_statuses) != 1:
    raise SystemExit(f"FAIL identical-key requests returned inconsistent statuses: {sorted(duplicate_statuses)}")

successful_duplicates = [json.loads((root / f"d-{i}.json").read_text()) for i, status in enumerate(statuses_d) if status == "200"]
if successful_duplicates:
    ids = {body["transfer_id"] for body in successful_duplicates}
    if len(ids) != 1:
        raise SystemExit(f"FAIL duplicate key returned multiple transfer IDs: {ids}")
    balances = {body["new_balance"] for body in successful_duplicates}
    if len(balances) != 1:
        raise SystemExit(f"FAIL duplicate key returned inconsistent balances: {balances}")
    duplicate_applied = 1
else:
    duplicate_applied = 0

if successful_a + duplicate_applied == 0:
    raise SystemExit("FAIL no transfer was applied; balance conservation was not meaningfully exercised")

(root / "expected.txt").write_text(
    f"{start_a - successful_a * amount + duplicate_applied * amount} "
    f"{start_b + successful_a * amount - duplicate_applied * amount}\n"
)
PY

status_a="$(request GET "$TOKEN_A" /accounts/me '' "$work_dir/final-a.json")"
status_b="$(request GET "$TOKEN_B" /accounts/me '' "$work_dir/final-b.json")"
[[ "$status_a" == 200 && "$status_b" == 200 ]] || { printf 'FAIL final balance read failed\n'; exit 1; }
final_a="$(json_number balance < "$work_dir/final-a.json")"
final_b="$(json_number balance < "$work_dir/final-b.json")"
read -r expected_a expected_b < "$work_dir/expected.txt"

if [[ "$final_a" != "$expected_a" || "$final_b" != "$expected_b" || $((final_a + final_b)) -ne $((start_a + start_b)) ]]; then
  printf 'FAIL balances: start=(%s,%s) expected=(%s,%s) actual=(%s,%s)\n' \
    "$start_a" "$start_b" "$expected_a" "$expected_b" "$final_a" "$final_b"
  exit 1
fi

printf 'PASS %s distinct requests plus %s identical-key retries; balances reconciled (%s + %s = %s)\n' \
  "$CONCURRENCY" "$CONCURRENCY" "$final_a" "$final_b" "$((final_a + final_b))"
