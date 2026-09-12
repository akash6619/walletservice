package com.walletservice.controller;

import com.walletservice.dto.CreateTransferRequest;
import com.walletservice.dto.TransferDetailsResponse;
import com.walletservice.dto.TransferResponse;
import com.walletservice.exception.InsufficientFundsException;
import com.walletservice.mapper.TransferMapper;
import com.walletservice.model.TransferResult;
import com.walletservice.model.TransferStatus;
import com.walletservice.security.AuthenticatedUser;
import com.walletservice.service.TransferQueryService;
import com.walletservice.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferQueryService transferQueryService;

    public TransferController(TransferService transferService, TransferQueryService transferQueryService) {
        this.transferService = transferService;
        this.transferQueryService = transferQueryService;
    }

    @PostMapping
    public TransferResponse transfer(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateTransferRequest request
    ) {
        TransferResult result = transferService.transfer(
                AuthenticatedUser.id(jwt),
                request.toUser(),
                request.amountPaise(),
                request.idempotencyKey()
        );
        if (result.status() == TransferStatus.REJECTED_INSUFFICIENT_FUNDS) {
            throw new InsufficientFundsException(result.transferId());
        }
        return TransferMapper.toResponse(result);
    }

    @GetMapping("/{id}")
    public TransferDetailsResponse get(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return TransferMapper.toDetailsResponse(
                transferQueryService.getVisibleTransfer(id, AuthenticatedUser.id(jwt))
        );
    }
}
