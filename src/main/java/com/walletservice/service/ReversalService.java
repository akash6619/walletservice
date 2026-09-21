package com.walletservice.service;

import com.walletservice.exception.IdempotencyConflictException;
import com.walletservice.exception.InsufficientFundsException;
import com.walletservice.exception.TransferAlreadyReversedException;
import com.walletservice.exception.TransferNotFoundException;
import com.walletservice.exception.UnregisteredCallerException;
import com.walletservice.model.Transfer;
import com.walletservice.model.TransferResult;
import com.walletservice.repository.TransferRepository;
import com.walletservice.repository.UserRepository;
import com.walletservice.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Performs a full reversal as one compensating transfer in the existing transfer table. */
@Service
public class ReversalService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;

    public ReversalService(UserRepository userRepository, WalletRepository walletRepository,
                           TransferRepository transferRepository) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public TransferResult reverse(UUID originalTransferId, UUID caller, String idempotencyKey) {
        validate(idempotencyKey);
        if (!userRepository.isActive(caller)) {
            throw new UnregisteredCallerException();
        }
        transferRepository.configureTransactionTimeouts();
        transferRepository.lockIdempotencyKey(caller, idempotencyKey);

        Transfer original = transferRepository.findReversibleForUpdate(originalTransferId, caller)
                .orElseThrow(TransferNotFoundException::new);
        Optional<Transfer> keyedTransfer = transferRepository.findBySenderAndKey(caller, idempotencyKey);
        if (keyedTransfer.isPresent()) {
            Transfer keyed = keyedTransfer.get();
            if (keyed.reversesTransferId() == null
                    || !keyed.reversesTransferId().equals(originalTransferId)) {
                throw new IdempotencyConflictException();
            }
            return TransferResult.replay(keyed);
        }
        Optional<Transfer> existing = transferRepository.findReversal(originalTransferId);
        if (existing.isPresent()) {
            throw new TransferAlreadyReversedException();
        }

        OptionalLong recipientBalance = walletRepository.debitIfSufficient(
                original.toUser(), original.amountPaise());
        if (recipientBalance.isEmpty()) {
            throw new InsufficientFundsException();
        }
        walletRepository.credit(original.fromUser(), original.amountPaise());

        UUID reversalId = UUID.randomUUID();
        transferRepository.insertAppliedReversal(
                reversalId, original, caller, idempotencyKey, recipientBalance.getAsLong());
        return TransferResult.applied(reversalId, recipientBalance.getAsLong(), false, false);
    }

    private static void validate(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 128
                || !idempotencyKey.chars().allMatch(character -> character >= 0x20 && character <= 0x7E)) {
            throw new com.walletservice.exception.InvalidTransferException(
                    "idempotency_key must contain 1 to 128 printable ASCII characters");
        }
    }
}
