package com.walletservice.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable persisted view of a transfer and its lifecycle state. */
public record Transfer(
        UUID transferId,
        UUID fromUser,
        UUID toUser,
        UUID initiatedBy,
        long amountPaise,
        String idempotencyKey,
        TransferType transferType,
        UUID reversesTransferId,
        TransferStatus status,
        Long senderBalanceAfter,
        Instant createdAt
) {
}
