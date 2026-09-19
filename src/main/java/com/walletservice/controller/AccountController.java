package com.walletservice.controller;

import com.walletservice.dto.AccountResponse;
import com.walletservice.mapper.AccountMapper;
import com.walletservice.model.AccountResult;
import com.walletservice.observability.BusinessObservability;
import com.walletservice.security.AuthenticatedUser;
import com.walletservice.service.AccountService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for creating and reading the authenticated user's wallet account.
 * The caller identity always comes from the verified JWT and is never accepted from request data.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final BusinessObservability observability;

    /** Creates the controller with its business and telemetry collaborators. */
    public AccountController(AccountService accountService, BusinessObservability observability) {
        this.accountService = accountService;
        this.observability = observability;
    }

    /**
     * Returns the caller's account, creating and funding its wallet when it does not yet exist.
     * Repeated calls are safe because creation is implemented as a database upsert.
     *
     * @param jwt verified bearer token supplied by Spring Security
     * @return the current account balance
     */
    @PostMapping
    public AccountResponse getOrCreate(@AuthenticationPrincipal Jwt jwt) {
        AccountResult result = accountService.getOrCreate(AuthenticatedUser.id(jwt));
        observability.accountUpsert(result);
        return AccountMapper.toResponse(result);
    }

    /**
     * Reads the caller's existing wallet without creating one.
     *
     * @param jwt verified bearer token supplied by Spring Security
     * @return the current account balance
     */
    @GetMapping("/me")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt) {
        return AccountMapper.toResponse(accountService.get(AuthenticatedUser.id(jwt)));
    }
}
