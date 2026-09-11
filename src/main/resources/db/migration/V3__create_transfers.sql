CREATE TABLE transfers (
    transfer_id UUID PRIMARY KEY,
    from_user UUID NOT NULL REFERENCES users(user_id),
    to_user UUID NOT NULL REFERENCES users(user_id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    idempotency_key VARCHAR(128) NOT NULL
        CHECK (LENGTH(idempotency_key) BETWEEN 1 AND 128),
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PROCESSING', 'APPLIED', 'REJECTED_INSUFFICIENT_FUNDS')),
    sender_balance_after BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (from_user <> to_user),
    CHECK (
        (status = 'APPLIED' AND sender_balance_after IS NOT NULL)
        OR
        (status IN ('PROCESSING', 'REJECTED_INSUFFICIENT_FUNDS')
            AND sender_balance_after IS NULL)
    ),
    UNIQUE (from_user, idempotency_key)
);

CREATE INDEX transfers_to_user_idx ON transfers(to_user);
