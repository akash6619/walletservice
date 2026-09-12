package com.walletservice.service;

import com.walletservice.exception.IdempotencyConflictException;
import com.walletservice.exception.InvalidTransferException;
import com.walletservice.exception.InvariantViolationException;
import com.walletservice.exception.RecipientNotFoundException;
import com.walletservice.exception.UnregisteredCallerException;
import com.walletservice.model.Transfer;
import com.walletservice.model.TransferResult;
import com.walletservice.model.TransferStatus;
import com.walletservice.repository.TransferRepository;
import com.walletservice.repository.UserRepository;
import com.walletservice.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

@Service
public class TransferService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final long initialBalancePaise;

    public TransferService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            TransferRepository transferRepository,
            @Value("${wallet.initial-balance-paise}") long initialBalancePaise
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.initialBalancePaise = initialBalancePaise;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public TransferResult transfer(UUID caller, UUID recipient, long amountPaise, String idempotencyKey) {
        validate(caller, recipient, amountPaise, idempotencyKey);
        requireActiveParticipants(caller, recipient);
        transferRepository.configureTransactionTimeouts();

        UUID candidateTransferId = UUID.randomUUID();
        Optional<UUID> claimedTransferId = transferRepository.claim(
                candidateTransferId,
                caller,
                recipient,
                amountPaise,
                idempotencyKey
        );

        if (claimedTransferId.isEmpty()) {
            return replayExisting(caller, recipient, amountPaise, idempotencyKey);
        }

        WalletCreation walletCreation = createWalletsInStableOrder(caller, recipient);
        OptionalLong senderBalance = walletRepository.debitIfSufficient(caller, amountPaise);

        if (senderBalance.isEmpty()) {
            transferRepository.finalizeRejected(candidateTransferId);
            return TransferResult.insufficientFunds(
                    candidateTransferId,
                    walletCreation.senderCreated(),
                    walletCreation.recipientCreated()
            );
        }

        walletRepository.credit(recipient, amountPaise);
        transferRepository.finalizeApplied(candidateTransferId, senderBalance.getAsLong());
        return TransferResult.applied(
                candidateTransferId,
                senderBalance.getAsLong(),
                walletCreation.senderCreated(),
                walletCreation.recipientCreated()
        );
    }

    private TransferResult replayExisting(
            UUID caller,
            UUID recipient,
            long amountPaise,
            String idempotencyKey
    ) {
        Transfer existing = transferRepository.findBySenderAndKey(caller, idempotencyKey)
                .orElseThrow(() -> new InvariantViolationException(
                        "Idempotency conflict occurred without a visible transfer"
                ));

        if (!existing.toUser().equals(recipient) || existing.amountPaise() != amountPaise) {
            throw new IdempotencyConflictException();
        }
        if (existing.status() == TransferStatus.PROCESSING) {
            throw new InvariantViolationException("A committed transfer cannot remain PROCESSING");
        }
        return TransferResult.replay(existing);
    }

    private WalletCreation createWalletsInStableOrder(UUID sender, UUID recipient) {
        boolean senderCreated;
        boolean recipientCreated;
        if (sender.compareTo(recipient) < 0) {
            senderCreated = walletRepository.createIfAbsent(sender, initialBalancePaise);
            recipientCreated = walletRepository.createIfAbsent(recipient, initialBalancePaise);
        } else {
            recipientCreated = walletRepository.createIfAbsent(recipient, initialBalancePaise);
            senderCreated = walletRepository.createIfAbsent(sender, initialBalancePaise);
        }
        return new WalletCreation(senderCreated, recipientCreated);
    }

    private void requireActiveParticipants(UUID caller, UUID recipient) {
        if (!userRepository.isActive(caller)) {
            throw new UnregisteredCallerException();
        }
        if (!userRepository.isActive(recipient)) {
            throw new RecipientNotFoundException();
        }
    }

    private static void validate(UUID caller, UUID recipient, long amountPaise, String idempotencyKey) {
        if (caller == null || recipient == null) {
            throw new InvalidTransferException("Sender and recipient are required");
        }
        if (caller.equals(recipient)) {
            throw new InvalidTransferException("Self-transfers are not allowed");
        }
        if (amountPaise <= 0) {
            throw new InvalidTransferException("amount_paise must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 128) {
            throw new InvalidTransferException("idempotency_key must contain 1 to 128 characters");
        }
        if (!idempotencyKey.chars().allMatch(character -> character >= 0x20 && character <= 0x7E)) {
            throw new InvalidTransferException("idempotency_key must contain printable ASCII only");
        }
    }

    private record WalletCreation(boolean senderCreated, boolean recipientCreated) {
    }
}
