package com.walletservice.service;

import com.walletservice.exception.TransferNotFoundException;
import com.walletservice.exception.UnregisteredCallerException;
import com.walletservice.model.Transfer;
import com.walletservice.repository.TransferRepository;
import com.walletservice.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransferQueryService {

    private final UserRepository userRepository;
    private final TransferRepository transferRepository;

    public TransferQueryService(UserRepository userRepository, TransferRepository transferRepository) {
        this.userRepository = userRepository;
        this.transferRepository = transferRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Transfer getVisibleTransfer(UUID transferId, UUID caller) {
        if (!userRepository.isActive(caller)) {
            throw new UnregisteredCallerException();
        }
        return transferRepository.findVisibleById(transferId, caller)
                .orElseThrow(TransferNotFoundException::new);
    }
}
