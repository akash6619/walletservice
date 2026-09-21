package com.walletservice.service;

import com.walletservice.exception.InsufficientFundsException;
import com.walletservice.exception.TransferAlreadyReversedException;
import com.walletservice.exception.TransferNotFoundException;
import com.walletservice.model.Transfer;
import com.walletservice.model.TransferResult;
import com.walletservice.model.TransferStatus;
import com.walletservice.model.TransferType;
import com.walletservice.repository.TransferRepository;
import com.walletservice.repository.UserRepository;
import com.walletservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReversalServiceTest {

    private static final UUID ORIGINAL_ID = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();

    @Mock UserRepository userRepository;
    @Mock WalletRepository walletRepository;
    @Mock TransferRepository transferRepository;

    private ReversalService service;
    private Transfer original;

    @BeforeEach
    void setUp() {
        service = new ReversalService(userRepository, walletRepository, transferRepository);
        original = transfer(ORIGINAL_ID, SENDER, RECIPIENT, SENDER, "payment", TransferType.TRANSFER, null);
    }

    @Test
    void createsAppliedCompensatingTransfer() {
        when(userRepository.isActive(SENDER)).thenReturn(true);
        when(transferRepository.findReversibleForUpdate(ORIGINAL_ID, SENDER)).thenReturn(Optional.of(original));
        when(transferRepository.findBySenderAndKey(SENDER, "reverse-1")).thenReturn(Optional.empty());
        when(transferRepository.findReversal(ORIGINAL_ID)).thenReturn(Optional.empty());
        when(walletRepository.debitIfSufficient(RECIPIENT, 100)).thenReturn(OptionalLong.of(900));

        TransferResult result = service.reverse(ORIGINAL_ID, SENDER, "reverse-1");

        assertThat(result.status()).isEqualTo(TransferStatus.APPLIED);
        InOrder order = inOrder(walletRepository, transferRepository);
        order.verify(transferRepository).lockIdempotencyKey(SENDER, "reverse-1");
        order.verify(walletRepository).debitIfSufficient(RECIPIENT, 100);
        order.verify(walletRepository).credit(SENDER, 100);
        order.verify(transferRepository).insertAppliedReversal(
                result.transferId(), original, SENDER, "reverse-1", 900);
    }

    @Test
    void hidesTransferFromAnyoneExceptOriginalSenderAndRejectsReversalRows() {
        when(userRepository.isActive(RECIPIENT)).thenReturn(true);
        when(transferRepository.findReversibleForUpdate(ORIGINAL_ID, RECIPIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reverse(ORIGINAL_ID, RECIPIENT, "reverse-2"))
                .isInstanceOf(TransferNotFoundException.class);
        verify(walletRepository, never()).debitIfSufficient(RECIPIENT, 100);
    }

    @Test
    void replaysSameReversalKeyWithoutMovingMoney() {
        UUID reversalId = UUID.randomUUID();
        Transfer reversal = transfer(
                reversalId, RECIPIENT, SENDER, SENDER, "reverse-3", TransferType.REVERSAL, ORIGINAL_ID);
        when(userRepository.isActive(SENDER)).thenReturn(true);
        when(transferRepository.findReversibleForUpdate(ORIGINAL_ID, SENDER)).thenReturn(Optional.of(original));
        when(transferRepository.findBySenderAndKey(SENDER, "reverse-3")).thenReturn(Optional.of(reversal));

        TransferResult result = service.reverse(ORIGINAL_ID, SENDER, "reverse-3");

        assertThat(result.transferId()).isEqualTo(reversalId);
        assertThat(result.replay()).isTrue();
        verify(walletRepository, never()).debitIfSufficient(RECIPIENT, 100);
    }

    @Test
    void preventsSecondReversalWithAnotherKey() {
        Transfer reversal = transfer(
                UUID.randomUUID(), RECIPIENT, SENDER, SENDER, "first-key", TransferType.REVERSAL, ORIGINAL_ID);
        when(userRepository.isActive(SENDER)).thenReturn(true);
        when(transferRepository.findReversibleForUpdate(ORIGINAL_ID, SENDER)).thenReturn(Optional.of(original));
        when(transferRepository.findBySenderAndKey(SENDER, "second-key")).thenReturn(Optional.empty());
        when(transferRepository.findReversal(ORIGINAL_ID)).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> service.reverse(ORIGINAL_ID, SENDER, "second-key"))
                .isInstanceOf(TransferAlreadyReversedException.class);
        verify(walletRepository, never()).debitIfSufficient(RECIPIENT, 100);
    }

    @Test
    void insufficientFundsCreatesNoReversal() {
        when(userRepository.isActive(SENDER)).thenReturn(true);
        when(transferRepository.findReversibleForUpdate(ORIGINAL_ID, SENDER)).thenReturn(Optional.of(original));
        when(transferRepository.findBySenderAndKey(SENDER, "reverse-4")).thenReturn(Optional.empty());
        when(transferRepository.findReversal(ORIGINAL_ID)).thenReturn(Optional.empty());
        when(walletRepository.debitIfSufficient(RECIPIENT, 100)).thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> service.reverse(ORIGINAL_ID, SENDER, "reverse-4"))
                .isInstanceOf(InsufficientFundsException.class);
        verify(walletRepository, never()).credit(SENDER, 100);
        verify(transferRepository, never()).insertAppliedReversal(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static Transfer transfer(UUID id, UUID from, UUID to, UUID initiatedBy, String key,
                                     TransferType type, UUID reverses) {
        return new Transfer(id, from, to, initiatedBy, 100, key, type, reverses,
                TransferStatus.APPLIED, 900L, Instant.now());
    }
}
