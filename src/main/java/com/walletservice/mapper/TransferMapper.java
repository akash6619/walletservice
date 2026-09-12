package com.walletservice.mapper;

import com.walletservice.dto.TransferDetailsResponse;
import com.walletservice.dto.TransferResponse;
import com.walletservice.exception.InvariantViolationException;
import com.walletservice.model.Transfer;
import com.walletservice.model.TransferResult;

public final class TransferMapper {

    private TransferMapper() {
    }

    public static TransferResponse toResponse(TransferResult result) {
        if (result.senderBalanceAfter() == null) {
            throw new InvariantViolationException("Applied transfer result is missing the sender balance");
        }
        return new TransferResponse(result.transferId(), result.senderBalanceAfter());
    }

    public static TransferDetailsResponse toDetailsResponse(Transfer transfer) {
        return new TransferDetailsResponse(
                transfer.transferId(),
                transfer.fromUser(),
                transfer.toUser(),
                transfer.amountPaise(),
                transfer.status(),
                transfer.createdAt()
        );
    }
}
