package com.walletservice.service;

import com.walletservice.exception.AccountNotFoundException;
import com.walletservice.exception.UnregisteredCallerException;
import com.walletservice.model.AccountResult;
import com.walletservice.model.Wallet;
import com.walletservice.repository.UserRepository;
import com.walletservice.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Coordinates account lifecycle operations for active registered users.
 * Wallet creation and the subsequent read run in one bounded transaction so callers observe the
 * row created either by this request or by a concurrent request.
 */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final long initialBalancePaise;

    /** Creates the service and supplies the configured opening balance for new wallets. */
    public AccountService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            @Value("${wallet.initial-balance-paise}") long initialBalancePaise
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.initialBalancePaise = initialBalancePaise;
    }

    /**
     * Gets or atomically creates the caller's wallet.
     *
     * @param caller authenticated user identifier
     * @return balance and whether this transaction won wallet creation
     * @throws UnregisteredCallerException when the caller is absent or inactive
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public AccountResult getOrCreate(UUID caller) {
        requireActiveCaller(caller);
        boolean created = walletRepository.createIfAbsent(caller, initialBalancePaise);
        Wallet wallet = walletRepository.findByUserId(caller)
                .orElseThrow(() -> new AccountNotFoundException());
        return new AccountResult(wallet.balancePaise(), created);
    }

    /**
     * Reads an existing wallet for an active caller without provisioning it.
     *
     * @param caller authenticated user identifier
     * @return current balance with {@code created=false}
     * @throws AccountNotFoundException when the caller has no wallet
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public AccountResult get(UUID caller) {
        requireActiveCaller(caller);
        Wallet wallet = walletRepository.findByUserId(caller)
                .orElseThrow(AccountNotFoundException::new);
        return new AccountResult(wallet.balancePaise(), false);
    }

    /** Rejects identities that are unknown or administratively inactive. */
    private void requireActiveCaller(UUID caller) {
        if (!userRepository.isActive(caller)) {
            throw new UnregisteredCallerException();
        }
    }
}
