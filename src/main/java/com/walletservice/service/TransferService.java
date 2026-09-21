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

/**
 * Executes idempotent wallet-to-wallet transfers in a single database transaction.
 *
 * <p>The transfer row is claimed before balances change. PostgreSQL's unique constraint on sender
 * and idempotency key serializes concurrent duplicates: one request performs the movement while
 * the others replay its committed result. Debit is a conditional SQL update, preventing concurrent
 * requests from overdrawing a wallet without relying on an application-side balance check.</p>
 */
@Service
public class TransferService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final long initialBalancePaise;

    /** Creates the service and supplies the configured opening balance for lazily created wallets. */
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

    /**
     * Applies a transfer or returns the earlier result associated with the same idempotency key.
     * All claim, wallet, debit, credit, and finalization writes commit or roll back together.
     *
     * @param caller authenticated sender
     * @param recipient active user receiving funds
     * @param amountPaise positive amount in the currency's smallest unit
     * @param idempotencyKey sender-scoped key used to safely retry the request
     * @return the applied, rejected, or replayed transfer result
     * @throws IdempotencyConflictException if the key was used for a different request body
     * @throws InvalidTransferException if the command violates input-level transfer rules
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public TransferResult transfer(UUID caller, UUID recipient, long amountPaise, String idempotencyKey) {
        validate(caller, recipient, amountPaise, idempotencyKey);
        requireActiveParticipants(caller, recipient);
        transferRepository.configureTransactionTimeouts();
        transferRepository.lockIdempotencyKey(caller, idempotencyKey);

        // Claiming first makes the transfer row both the idempotency record and the concurrency gate.
        UUID candidateTransferId = UUID.randomUUID();
        Optional<UUID> claimedTransferId = transferRepository.claim(
                candidateTransferId,
                caller,
                recipient,
                amountPaise,
                idempotencyKey
        );

        // An empty result means another transaction owns or has completed this sender/key pair.
        if (claimedTransferId.isEmpty()) {
            return replayExisting(caller, recipient, amountPaise, idempotencyKey);
        }

        // Wallets are provisioned before moving money, but remain part of this transaction.
        WalletCreation walletCreation = createWalletsInStableOrder(caller, recipient);
        OptionalLong senderBalance = walletRepository.debitIfSufficient(caller, amountPaise);

        // Conditional debit distinguishes insufficient funds without a racy read-then-write sequence.
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

    /**
     * Validates that a duplicate key represents the same logical command and replays its terminal
     * result. A visible {@code PROCESSING} row after the claiming statement completes would violate
     * the transaction protocol, because conflicting inserts wait for the owner to commit or abort.
     */
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

    /**
     * Creates sender and recipient wallets in UUID order. Consistent lock acquisition order reduces
     * deadlock risk when concurrent transfers involve the same pair in opposite directions.
     */
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

    /** Ensures both sides refer to active registered users before any wallet is provisioned. */
    private void requireActiveParticipants(UUID caller, UUID recipient) {
        if (!userRepository.isActive(caller)) {
            throw new UnregisteredCallerException();
        }
        if (!userRepository.isActive(recipient)) {
            throw new RecipientNotFoundException();
        }
    }

    /**
     * Enforces domain rules independently of HTTP validation so non-controller callers are safe.
     */
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

    /** Tracks which lazy wallet inserts were won by the current transaction for telemetry. */
    private record WalletCreation(boolean senderCreated, boolean recipientCreated) {
    }
}
