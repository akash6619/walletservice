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

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final BusinessObservability observability;

    public AccountController(AccountService accountService, BusinessObservability observability) {
        this.accountService = accountService;
        this.observability = observability;
    }

    @PostMapping
    public AccountResponse getOrCreate(@AuthenticationPrincipal Jwt jwt) {
        AccountResult result = accountService.getOrCreate(AuthenticatedUser.id(jwt));
        observability.accountUpsert(result);
        return AccountMapper.toResponse(result);
    }

    @GetMapping("/me")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt) {
        return AccountMapper.toResponse(accountService.get(AuthenticatedUser.id(jwt)));
    }
}
