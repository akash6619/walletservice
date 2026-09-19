package com.walletservice.mapper;

import com.walletservice.dto.AccountResponse;
import com.walletservice.model.AccountResult;

/** Converts internal account results into the public API contract. */
public final class AccountMapper {

    /** Prevents instantiation of this stateless mapping utility. */
    private AccountMapper() {
    }

    /** Maps an account result while intentionally omitting the internal creation flag. */
    public static AccountResponse toResponse(AccountResult result) {
        return new AccountResponse(result.balancePaise());
    }
}
