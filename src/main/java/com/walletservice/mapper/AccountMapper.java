package com.walletservice.mapper;

import com.walletservice.dto.AccountResponse;
import com.walletservice.model.AccountResult;

public final class AccountMapper {

    private AccountMapper() {
    }

    public static AccountResponse toResponse(AccountResult result) {
        return new AccountResponse(result.balancePaise());
    }
}
