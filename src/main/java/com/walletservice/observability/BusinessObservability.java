package com.walletservice.observability;

import com.walletservice.model.AccountResult;
import com.walletservice.model.TransferResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emits low-cardinality business metrics and structured lifecycle events.
 * User IDs, transfer IDs, and idempotency keys are intentionally excluded from metric tags.
 */
@Component
public class BusinessObservability {

    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessObservability.class);

    private final MeterRegistry meterRegistry;

    /** Creates the telemetry component using the application's Micrometer registry. */
    public BusinessObservability(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Records whether an account upsert created a wallet or found an existing one. */
    public void accountUpsert(AccountResult result) {
        walletUpsert(result.created());
    }

    /** Records one terminal transfer outcome and any wallet provisioning performed with it. */
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

    /** Records reuse of an idempotency key with a conflicting request body. */
    public void idempotencyConflict() {
        event("idempotency_conflict");
        transfer("conflict");
    }

    /** Records an authentication or authorization failure emitted by the security chain. */
    public void authFailed() {
        event("auth_failed");
    }

    /** Records a database condition for which the API explicitly recommends retrying. */
    public void retryableDatabaseFailure() {
        event("database_retryable_failure");
    }

    /** Emits wallet-upsert metrics with a bounded created/existing outcome tag. */
    private void walletUpsert(boolean created) {
        String outcome = created ? "created" : "existing";
        event(created ? "wallet_create_won" : "wallet_create_conflict");
        meterRegistry.counter("wallet_upserts_total", "outcome", outcome).increment();
    }

    /** Increments the transfer counter for a bounded terminal outcome. */
    private void transfer(String outcome) {
        meterRegistry.counter("transfers_total", "outcome", outcome).increment();
    }

    /** Writes a structured business event; the correlation ID is supplied by MDC configuration. */
    private static void event(String event) {
        LOGGER.atInfo().addKeyValue("event", event).log(event);
    }
}
