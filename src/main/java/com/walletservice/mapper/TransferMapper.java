package com.walletservice.mapper;

import com.walletservice.dto.TransferDetailsResponse;
import com.walletservice.dto.TransferResponse;
import com.walletservice.exception.InvariantViolationException;
import com.walletservice.model.Transfer;
import com.walletservice.model.TransferResult;

/** Converts transfer domain objects into command and query API representations. */
public final class TransferMapper {

    /** Prevents instantiation of this stateless mapping utility. */
    private TransferMapper() {
    }

    /**
     * Maps an applied or replayed-success result to its response.
     * A missing balance signals an invalid call-site/state combination rather than client input.
     */
    public static TransferResponse toResponse(TransferResult result) {
        if (result.senderBalanceAfter() == null) {
            throw new InvariantViolationException("Applied transfer result is missing the sender balance");
        }
        return new TransferResponse(result.transferId(), result.senderBalanceAfter());
    }

    /** Maps a finalized persisted transfer to participant-visible details. */
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
