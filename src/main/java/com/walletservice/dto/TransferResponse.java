package com.walletservice.dto;

import java.util.UUID;

/** Successful transfer response containing the ID and sender's resulting balance in paise. */
public record TransferResponse(UUID transferId, long newBalance) {
}
