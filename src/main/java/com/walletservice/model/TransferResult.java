package com.walletservice.model;

import java.util.UUID;

public record TransferResult(
        UUID transferId,
        TransferStatus status,
        Long senderBalanceAfter,
        boolean replay,
        boolean senderWalletCreated,
        boolean recipientWalletCreated
) {

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
