package com.walletservice.dto;

import java.util.UUID;

/** Successful reversal response linking the compensating transfer to its original. */
public record ReversalResponse(UUID reversalTransferId, UUID originalTransferId) {
}
