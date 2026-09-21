package com.walletservice.dto;

import com.walletservice.model.TransferStatus;
import com.walletservice.model.TransferType;

import java.time.Instant;
import java.util.UUID;

/** Participant-visible, finalized transfer representation returned by the query endpoint. */
public record TransferDetailsResponse(
        UUID transferId,
        UUID fromUser,
        UUID toUser,
        long amountPaise,
        TransferType transferType,
        UUID reversesTransferId,
        TransferStatus status,
        Instant createdAt
) {
}
