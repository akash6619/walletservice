package com.walletservice.observability;

import com.walletservice.model.AccountResult;
import com.walletservice.model.TransferResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BusinessObservability {

    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessObservability.class);

    private final MeterRegistry meterRegistry;

    public BusinessObservability(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void accountUpsert(AccountResult result) {
        walletUpsert(result.created());
    }

    public void transferCompleted(TransferResult result) {
        if (result.replay()) {
            event("idempotent_replay");
            transfer("replay");
            return;
        }

        walletUpsert(result.senderWalletCreated());
        walletUpsert(result.recipientWalletCreated());
        switch (result.status()) {
            case APPLIED -> {
                event("transfer_applied");
                transfer("applied");
            }
            case REJECTED_INSUFFICIENT_FUNDS -> {
                event("insufficient_funds");
                transfer("insufficient");
            }
            case PROCESSING -> throw new IllegalArgumentException("A completed transfer cannot be PROCESSING");
        }
    }

    public void idempotencyConflict() {
        event("idempotency_conflict");
        transfer("conflict");
    }

    public void authFailed() {
        event("auth_failed");
    }

    public void retryableDatabaseFailure() {
        event("database_retryable_failure");
    }

    private void walletUpsert(boolean created) {
        String outcome = created ? "created" : "existing";
        event(created ? "wallet_create_won" : "wallet_create_conflict");
        meterRegistry.counter("wallet_upserts_total", "outcome", outcome).increment();
    }

    private void transfer(String outcome) {
        meterRegistry.counter("transfers_total", "outcome", outcome).increment();
    }

    private static void event(String event) {
        LOGGER.atInfo().addKeyValue("event", event).log(event);
    }
}
