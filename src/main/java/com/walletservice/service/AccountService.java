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

@Service
public class AccountService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final long initialBalancePaise;

    public AccountService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            @Value("${wallet.initial-balance-paise}") long initialBalancePaise
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.initialBalancePaise = initialBalancePaise;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public AccountResult getOrCreate(UUID caller) {
        requireActiveCaller(caller);
        boolean created = walletRepository.createIfAbsent(caller, initialBalancePaise);
        Wallet wallet = walletRepository.findByUserId(caller)
                .orElseThrow(() -> new AccountNotFoundException());
        return new AccountResult(wallet.balancePaise(), created);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public AccountResult get(UUID caller) {
        requireActiveCaller(caller);
        Wallet wallet = walletRepository.findByUserId(caller)
                .orElseThrow(AccountNotFoundException::new);
        return new AccountResult(wallet.balancePaise(), false);
    }

    private void requireActiveCaller(UUID caller) {
        if (!userRepository.isActive(caller)) {
            throw new UnregisteredCallerException();
        }
    }
}
