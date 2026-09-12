package com.walletservice.dto;

import com.walletservice.model.TransferStatus;

import java.time.Instant;
import java.util.UUID;

public record TransferDetailsResponse(
        UUID transferId,
        UUID fromUser,
        UUID toUser,
        long amountPaise,
        TransferStatus status,
        Instant createdAt
) {
}
