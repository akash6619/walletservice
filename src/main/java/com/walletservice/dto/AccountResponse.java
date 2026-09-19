package com.walletservice.dto;

/** Public account representation, with {@code balance} expressed in paise. */
public record AccountResponse(long balance) {
}
