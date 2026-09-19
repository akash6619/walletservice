package com.walletservice.controller;

import com.walletservice.dto.CreateTransferRequest;
import com.walletservice.dto.TransferDetailsResponse;
import com.walletservice.dto.TransferResponse;
import com.walletservice.exception.InsufficientFundsException;
import com.walletservice.mapper.TransferMapper;
import com.walletservice.model.TransferResult;
import com.walletservice.model.TransferStatus;
import com.walletservice.observability.BusinessObservability;
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

/**
 * Exposes authenticated money-transfer commands and participant-scoped transfer queries.
 * Domain results are translated into stable API DTOs and HTTP errors by this layer.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferQueryService transferQueryService;
    private final BusinessObservability observability;

    /** Creates the controller with command, query, and observability collaborators. */
    public TransferController(
            TransferService transferService,
            TransferQueryService transferQueryService,
            BusinessObservability observability
    ) {
        this.transferService = transferService;
        this.transferQueryService = transferQueryService;
        this.observability = observability;
    }

    /**
     * Executes or replays an idempotent transfer for the authenticated sender.
     * A persisted insufficient-funds result is deliberately converted to an API exception after
     * telemetry is recorded; successful and replayed transfers return the same response shape.
     *
     * @param jwt verified bearer token identifying the sender
     * @param request validated transfer body
     * @return the transfer identifier and sender's post-transfer balance
     */
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
        observability.transferCompleted(result);
        if (result.status() == TransferStatus.REJECTED_INSUFFICIENT_FUNDS) {
            throw new InsufficientFundsException(result.transferId());
        }
        return TransferMapper.toResponse(result);
    }

    /**
     * Returns a finalized transfer only when the caller is its sender or recipient.
     *
     * @param id transfer identifier from the route
     * @param jwt verified bearer token identifying the caller
     * @return participant-visible transfer details
     */
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
