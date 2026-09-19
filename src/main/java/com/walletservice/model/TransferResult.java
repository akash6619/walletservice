package com.walletservice.model;

import java.util.UUID;

/**
 * Outcome returned by the transfer command, including flags needed for business telemetry.
 * {@code senderBalanceAfter} is absent for a rejected insufficient-funds transfer.
 */
public record TransferResult(
        UUID transferId,
        TransferStatus status,
        Long senderBalanceAfter,
        boolean replay,
        boolean senderWalletCreated,
        boolean recipientWalletCreated
) {

    /** Builds a result from an already committed transfer reached through an idempotent retry. */
    public static TransferResult replay(Transfer transfer) {
        return new TransferResult(
                transfer.transferId(),
                transfer.status(),
                transfer.senderBalanceAfter(),
                true,
                false,
                false
        );
    }

    /** Builds the terminal result of a newly applied transfer. */
    public static TransferResult applied(
            UUID transferId,
            long senderBalanceAfter,
            boolean senderWalletCreated,
            boolean recipientWalletCreated
    ) {
        return new TransferResult(
                transferId,
                TransferStatus.APPLIED,
                senderBalanceAfter,
                false,
                senderWalletCreated,
                recipientWalletCreated
        );
    }

    /** Builds the terminal result of a newly rejected transfer. */
    public static TransferResult insufficientFunds(
            UUID transferId,
            boolean senderWalletCreated,
            boolean recipientWalletCreated
    ) {
        return new TransferResult(
                transferId,
                TransferStatus.REJECTED_INSUFFICIENT_FUNDS,
                null,
                false,
                senderWalletCreated,
                recipientWalletCreated
        );
    }
}
