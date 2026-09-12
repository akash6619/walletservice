package com.walletservice.service;

import com.walletservice.exception.AccountNotFoundException;
import com.walletservice.exception.UnregisteredCallerException;
import com.walletservice.model.AccountResult;
import com.walletservice.model.Wallet;
import com.walletservice.repository.UserRepository;
import com.walletservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long INITIAL_BALANCE = 100_000L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private WalletRepository walletRepository;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(userRepository, walletRepository, INITIAL_BALANCE);
    }

    @Test
    void createsWalletOnceAndReturnsItsStoredBalance() {
        Wallet wallet = wallet(INITIAL_BALANCE);
        when(userRepository.isActive(USER_ID)).thenReturn(true);
        when(walletRepository.createIfAbsent(USER_ID, INITIAL_BALANCE)).thenReturn(true);
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.of(wallet));

        AccountResult result = service.getOrCreate(USER_ID);

        assertThat(result.balancePaise()).isEqualTo(INITIAL_BALANCE);
        assertThat(result.created()).isTrue();
    }

    @Test
    void rejectsAnUnregisteredCaller() {
        when(userRepository.isActive(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getOrCreate(USER_ID))
                .isInstanceOf(UnregisteredCallerException.class);
    }

    @Test
    void getRejectsMissingWallet() {
        when(userRepository.isActive(USER_ID)).thenReturn(true);
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID))
                .isInstanceOf(AccountNotFoundException.class);
    }

    private static Wallet wallet(long balance) {
        Instant now = Instant.now();
        return new Wallet(UUID.randomUUID(), USER_ID, balance, now, now);
    }
}
