package com.walletservice.service;

import com.walletservice.exception.IdempotencyConflictException;
import com.walletservice.exception.InvalidTransferException;
import com.walletservice.model.Transfer;
import com.walletservice.model.TransferResult;
import com.walletservice.model.TransferStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long INITIAL_BALANCE = 100_000L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private TransferRepository transferRepository;

    private TransferService service;

    @BeforeEach
    void setUp() {
        service = new TransferService(
                userRepository,
                walletRepository,
                transferRepository,
                INITIAL_BALANCE
        );
    }

    @Test
    void appliesTransferAndFinalizesAfterBothBalanceMutations() {
        when(userRepository.isActive(SENDER)).thenReturn(true);
        when(userRepository.isActive(RECIPIENT)).thenReturn(true);
        when(transferRepository.claim(any(), any(), any(), anyLong(), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        when(walletRepository.createIfAbsent(SENDER, INITIAL_BALANCE)).thenReturn(false);
        when(walletRepository.createIfAbsent(RECIPIENT, INITIAL_BALANCE)).thenReturn(true);
        when(walletRepository.debitIfSufficient(SENDER, 1_500L)).thenReturn(OptionalLong.of(98_500L));

        TransferResult result = service.transfer(SENDER, RECIPIENT, 1_500L, "payment-1");

        assertThat(result.status()).isEqualTo(TransferStatus.APPLIED);
        assertThat(result.senderBalanceAfter()).isEqualTo(98_500L);
        assertThat(result.replay()).isFalse();
        assertThat(result.senderWalletCreated()).isFalse();
        assertThat(result.recipientWalletCreated()).isTrue();

        InOrder moneyOrder = inOrder(walletRepository, transferRepository);
        moneyOrder.verify(transferRepository).configureTransactionTimeouts();
        moneyOrder.verify(transferRepository).lockIdempotencyKey(SENDER, "payment-1");
        moneyOrder.verify(transferRepository).claim(any(), any(), any(), anyLong(), any());
        moneyOrder.verify(walletRepository).createIfAbsent(SENDER, INITIAL_BALANCE);
        moneyOrder.verify(walletRepository).createIfAbsent(RECIPIENT, INITIAL_BALANCE);
        moneyOrder.verify(walletRepository).debitIfSufficient(SENDER, 1_500L);
        moneyOrder.verify(walletRepository).credit(RECIPIENT, 1_500L);
        moneyOrder.verify(transferRepository).finalizeApplied(result.transferId(), 98_500L);
    }

    @Test
    void persistsInsufficientFundsWithoutCreditingRecipient() {
        when(userRepository.isActive(SENDER)).thenReturn(true);
        when(userRepository.isActive(RECIPIENT)).thenReturn(true);
        when(transferRepository.claim(any(), any(), any(), anyLong(), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        when(walletRepository.debitIfSufficient(SENDER, 200_000L)).thenReturn(OptionalLong.empty());

        TransferResult result = service.transfer(SENDER, RECIPIENT, 200_000L, "payment-2");

        assertThat(result.status()).isEqualTo(TransferStatus.REJECTED_INSUFFICIENT_FUNDS);
        assertThat(result.senderBalanceAfter()).isNull();
        verify(walletRepository, never()).credit(any(), anyLong());
        verify(transferRepository).finalizeRejected(result.transferId());
    }

    @Test
    void replaysTerminalOutcomeWithoutTouchingWallets() {
        UUID existingId = UUID.randomUUID();
        Transfer existing = new Transfer(
                existingId,
                SENDER,
                RECIPIENT,
                SENDER,
                500L,
                "payment-3",
                com.walletservice.model.TransferType.TRANSFER,
                null,
                TransferStatus.APPLIED,
                99_500L,
                Instant.now()
        );
        when(userRepository.isActive(SENDER)).thenReturn(true);
        when(userRepository.isActive(RECIPIENT)).thenReturn(true);
        when(transferRepository.claim(any(), any(), any(), anyLong(), any()))
                .thenReturn(Optional.empty());
        when(transferRepository.findBySenderAndKey(SENDER, "payment-3"))
                .thenReturn(Optional.of(existing));

        TransferResult result = service.transfer(SENDER, RECIPIENT, 500L, "payment-3");

        assertThat(result.transferId()).isEqualTo(existingId);
        assertThat(result.replay()).isTrue();
        assertThat(result.senderBalanceAfter()).isEqualTo(99_500L);
        verify(walletRepository, never()).createIfAbsent(any(), anyLong());
        verify(walletRepository, never()).debitIfSufficient(any(), anyLong());
        verify(walletRepository, never()).credit(any(), anyLong());
    }

    @Test
    void rejectsSameKeyWithDifferentBodyWithoutTouchingWallets() {
        Transfer existing = new Transfer(
                UUID.randomUUID(),
                SENDER,
                RECIPIENT,
                SENDER,
                500L,
                "payment-4",
                com.walletservice.model.TransferType.TRANSFER,
                null,
                TransferStatus.REJECTED_INSUFFICIENT_FUNDS,
                null,
                Instant.now()
        );
        when(userRepository.isActive(SENDER)).thenReturn(true);
        when(userRepository.isActive(RECIPIENT)).thenReturn(true);
        when(transferRepository.claim(any(), any(), any(), anyLong(), any()))
                .thenReturn(Optional.empty());
        when(transferRepository.findBySenderAndKey(SENDER, "payment-4"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.transfer(SENDER, RECIPIENT, 501L, "payment-4"))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(walletRepository, never()).createIfAbsent(any(), anyLong());
        verify(walletRepository, never()).debitIfSufficient(any(), anyLong());
    }

    @Test
    void rejectsInvalidInputBeforeAccessingRepositories() {
        assertThatThrownBy(() -> service.transfer(SENDER, SENDER, 1L, "key"))
                .isInstanceOf(InvalidTransferException.class);
        assertThatThrownBy(() -> service.transfer(SENDER, RECIPIENT, 0L, "key"))
                .isInstanceOf(InvalidTransferException.class);
        assertThatThrownBy(() -> service.transfer(SENDER, RECIPIENT, 1L, "bad\nkey"))
                .isInstanceOf(InvalidTransferException.class);

        verify(userRepository, never()).isActive(any());
        verify(transferRepository, never()).claim(any(), any(), any(), anyLong(), any());
    }
}
