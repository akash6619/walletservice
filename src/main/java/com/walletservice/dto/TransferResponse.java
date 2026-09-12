package com.walletservice.dto;

import java.util.UUID;

public record TransferResponse(UUID transferId, long newBalance) {
}
