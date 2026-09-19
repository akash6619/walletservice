package com.walletservice.dto;

import com.walletservice.model.TransferStatus;

import java.time.Instant;
import java.util.UUID;

/** Participant-visible, finalized transfer representation returned by the query endpoint. */
public record TransferDetailsResponse(
        UUID transferId,
        UUID fromUser,
        UUID toUser,
        long amountPaise,
        TransferStatus status,
        Instant createdAt
) {
}
