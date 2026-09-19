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

/** Provides read-only, authorization-aware access to finalized transfers. */
@Service
public class TransferQueryService {

    private final UserRepository userRepository;
    private final TransferRepository transferRepository;

    /** Creates the query service with user and transfer persistence collaborators. */
    public TransferQueryService(UserRepository userRepository, TransferRepository transferRepository) {
        this.userRepository = userRepository;
        this.transferRepository = transferRepository;
    }

    /**
     * Loads a finalized transfer visible to the active caller.
     * Returning not-found for nonparticipants avoids revealing that a transfer exists.
     *
     * @param transferId transfer to retrieve
     * @param caller authenticated requesting user
     * @return the visible transfer
     * @throws TransferNotFoundException if absent, still processing, or not visible to the caller
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Transfer getVisibleTransfer(UUID transferId, UUID caller) {
        if (!userRepository.isActive(caller)) {
            throw new UnregisteredCallerException();
        }
        return transferRepository.findVisibleById(transferId, caller)
                .orElseThrow(TransferNotFoundException::new);
    }
}
