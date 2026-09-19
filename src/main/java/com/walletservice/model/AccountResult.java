package com.walletservice.model;

/**
 * Service-layer account result.
 *
 * @param balancePaise current wallet balance in paise
 * @param created whether the current operation created the wallet
 */
public record AccountResult(long balancePaise, boolean created) {
}
