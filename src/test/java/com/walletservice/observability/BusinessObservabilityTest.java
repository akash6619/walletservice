package com.walletservice.observability;

import com.walletservice.model.AccountResult;
import com.walletservice.model.TransferResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessObservabilityTest {

    @Test
    void recordsAppliedTransferAndBothWalletOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessObservability observability = new BusinessObservability(registry);

        observability.transferCompleted(TransferResult.applied(UUID.randomUUID(), 900L, true, false));

        assertThat(registry.counter("transfers_total", "outcome", "applied").count()).isEqualTo(1);
        assertThat(registry.counter("wallet_upserts_total", "outcome", "created").count()).isEqualTo(1);
        assertThat(registry.counter("wallet_upserts_total", "outcome", "existing").count()).isEqualTo(1);
    }

    @Test
    void recordsReplayWithoutReportingWalletUpserts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessObservability observability = new BusinessObservability(registry);

        observability.transferCompleted(new TransferResult(
                UUID.randomUUID(),
                com.walletservice.model.TransferStatus.APPLIED,
                900L,
                true,
                false,
                false
        ));

        assertThat(registry.counter("transfers_total", "outcome", "replay").count()).isEqualTo(1);
        assertThat(registry.find("wallet_upserts_total").meters()).isEmpty();
    }

    @Test
    void recordsReversalWithoutReportingTransferOrWalletUpsert() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessObservability observability = new BusinessObservability(registry);

        observability.reversalCompleted(
                TransferResult.applied(UUID.randomUUID(), 900L, false, false));

        assertThat(registry.counter("reversals_total", "outcome", "applied").count()).isEqualTo(1);
        assertThat(registry.find("transfers_total").meters()).isEmpty();
        assertThat(registry.find("wallet_upserts_total").meters()).isEmpty();
    }

    @Test
    void recordsAccountAndFailureOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessObservability observability = new BusinessObservability(registry);

        observability.accountUpsert(new AccountResult(1_000L, true));
        observability.idempotencyConflict();

        assertThat(registry.counter("wallet_upserts_total", "outcome", "created").count()).isEqualTo(1);
        assertThat(registry.counter("transfers_total", "outcome", "conflict").count()).isEqualTo(1);
    }

    @Test
    void exposesStablePrometheusCounterNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        BusinessObservability observability = new BusinessObservability(registry);

        observability.idempotencyConflict();
        observability.accountUpsert(new AccountResult(1_000L, false));

        assertThat(registry.scrape())
                .contains("transfers_total{outcome=\"conflict\"} 1.0")
                .contains("wallet_upserts_total{outcome=\"existing\"} 1.0")
                .doesNotContain("_total_total");
    }
}
