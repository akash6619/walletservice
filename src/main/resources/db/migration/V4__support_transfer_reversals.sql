ALTER TABLE transfers
    ADD COLUMN transfer_type VARCHAR(16) NOT NULL DEFAULT 'TRANSFER'
        CHECK (transfer_type IN ('TRANSFER', 'REVERSAL')),
    ADD COLUMN initiated_by UUID REFERENCES users(user_id),
    ADD COLUMN reverses_transfer_id UUID REFERENCES transfers(transfer_id);

UPDATE transfers SET initiated_by = from_user WHERE initiated_by IS NULL;

ALTER TABLE transfers
    ALTER COLUMN initiated_by SET NOT NULL,
    DROP CONSTRAINT transfers_from_user_idempotency_key_key,
    ADD CONSTRAINT transfers_initiator_idempotency_key_unique
        UNIQUE (initiated_by, idempotency_key),
    ADD CONSTRAINT transfers_reversal_shape_check CHECK (
        (transfer_type = 'TRANSFER' AND reverses_transfer_id IS NULL)
        OR
        (transfer_type = 'REVERSAL' AND reverses_transfer_id IS NOT NULL)
    );

CREATE UNIQUE INDEX one_reversal_per_original_transfer
    ON transfers(reverses_transfer_id)
    WHERE transfer_type = 'REVERSAL';
