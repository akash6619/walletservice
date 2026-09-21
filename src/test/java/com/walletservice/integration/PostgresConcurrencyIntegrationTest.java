package com.walletservice.integration;

import com.walletservice.model.AccountResult;
import com.walletservice.model.TransferResult;
import com.walletservice.model.TransferStatus;
import com.walletservice.exception.IdempotencyConflictException;
import com.walletservice.service.AccountService;
import com.walletservice.service.TransferService;
import com.walletservice.service.ReversalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "wallet.initial-balance-paise=1000",
        "wallet.jwt.secret=01234567890123456789012345678901"
})
@AutoConfigureMockMvc
class PostgresConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
            .withDatabaseName("wallet")
            .withUsername("wallet")
            .withPassword("wallet");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired ReversalService reversalService;
    @Autowired MockMvc mockMvc;

    private ExecutorService executor;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE transfers, wallets, users");
        executor = Executors.newFixedThreadPool(20);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void concurrentFirstUseCreatesExactlyOneWallet() throws Exception {
        UUID user = createUser("First User");

        List<AccountResult> results = runTogether(20, () -> accountService.getOrCreate(user));

        assertThat(results).allMatch(result -> result.balancePaise() == 1000);
        assertThat(results).filteredOn(AccountResult::created).hasSize(1);
        assertThat(count("wallets", "user_id", user)).isOne();
    }

    @Test
    void identicalConcurrentRequestsApplyExactlyOnce() throws Exception {
        UUID sender = createUser("Sender");
        UUID recipient = createUser("Recipient");

        List<TransferResult> results = runTogether(
                20,
                () -> transferService.transfer(sender, recipient, 125, "same-key")
        );

        assertThat(results).allMatch(result -> result.status() == TransferStatus.APPLIED);
        assertThat(results.stream().map(TransferResult::transferId).collect(java.util.stream.Collectors.toSet()))
                .hasSize(1);
        assertThat(results).filteredOn(result -> !result.replay()).hasSize(1);
        assertThat(balance(sender)).isEqualTo(875);
        assertThat(balance(recipient)).isEqualTo(1125);
        assertThat(count("transfers", "from_user", sender)).isOne();
    }

    @Test
    void identicalConcurrentReversalsApplyExactlyOnce() throws Exception {
        UUID sender = createUser("Sender");
        UUID recipient = createUser("Recipient");
        UUID originalId = transferService.transfer(sender, recipient, 125, "payment-key").transferId();

        List<TransferResult> results = runTogether(
                20,
                () -> reversalService.reverse(originalId, sender, "reversal-key")
        );

        assertThat(results.stream().map(TransferResult::transferId)
                .collect(java.util.stream.Collectors.toSet())).hasSize(1);
        assertThat(results).filteredOn(result -> !result.replay()).hasSize(1);
        assertThat(balance(sender)).isEqualTo(1000);
        assertThat(balance(recipient)).isEqualTo(1000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE reverses_transfer_id = ?",
                Long.class,
                originalId
        )).isOne();
    }

    @Test
    void sameReversalKeyForDifferentTransfersReturnsConflictWithoutExtraMutation() throws Exception {
        UUID sender = createUser("Sender");
        UUID firstRecipient = createUser("First Recipient");
        UUID secondRecipient = createUser("Second Recipient");
        UUID firstTransfer = transferService.transfer(sender, firstRecipient, 100, "payment-1").transferId();
        UUID secondTransfer = transferService.transfer(sender, secondRecipient, 100, "payment-2").transferId();

        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = List.of(
                executor.submit(() -> reverseOutcome(start, firstTransfer, sender)),
                executor.submit(() -> reverseOutcome(start, secondTransfer, sender))
        );
        start.countDown();

        assertThat(getAll(futures)).containsExactlyInAnyOrder("applied", "conflict");
        assertThat(balance(sender)).isEqualTo(900);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE transfer_type = 'REVERSAL'", Long.class)).isOne();
    }

    @Test
    void recipientCannotInitiateReversalAndReversalCannotBeReversed() throws Exception {
        UUID sender = createUser("Sender");
        UUID recipient = createUser("Recipient");
        UUID originalId = transferService.transfer(sender, recipient, 125, "payment-key").transferId();

        mockMvc.perform(post("/transfers/{id}/reversal", originalId)
                        .with(jwt().jwt(token -> token.subject(recipient.toString())))
                        .contentType("application/json")
                        .content("{\"idempotency_key\":\"unauthorized-reversal\"}"))
                .andExpect(status().isNotFound());

        UUID reversalId = reversalService.reverse(originalId, sender, "valid-reversal").transferId();
        mockMvc.perform(post("/transfers/{id}/reversal", reversalId)
                        .with(jwt().jwt(token -> token.subject(sender.toString())))
                        .contentType("application/json")
                        .content("{\"idempotency_key\":\"reverse-a-reversal\"}"))
                .andExpect(status().isNotFound());

        assertThat(balance(sender)).isEqualTo(1000);
        assertThat(balance(recipient)).isEqualTo(1000);
    }

    @Test
    void distinctConcurrentTransfersCannotOverspend() throws Exception {
        UUID sender = createUser("Sender");
        List<UUID> recipients = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            recipients.add(createUser("Recipient " + index));
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferResult>> futures = new ArrayList<>();
        for (int index = 0; index < recipients.size(); index++) {
            UUID recipient = recipients.get(index);
            String key = "distinct-" + index;
            futures.add(executor.submit(() -> {
                start.await();
                return transferService.transfer(sender, recipient, 100, key);
            }));
        }
        start.countDown();
        List<TransferResult> results = getAll(futures);

        assertThat(results).filteredOn(result -> result.status() == TransferStatus.APPLIED).hasSize(10);
        assertThat(results).filteredOn(result -> result.status() == TransferStatus.REJECTED_INSUFFICIENT_FUNDS)
                .hasSize(10);
        assertThat(balance(sender)).isZero();
        assertThat(jdbc.queryForObject("SELECT min(balance_paise) FROM wallets", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(balance_paise) FROM wallets", Long.class)).isEqualTo(21_000);
    }

    @Test
    void sameKeyWithDifferentBodyConflictsWithoutMutation() throws Exception {
        UUID sender = createUser("Sender");
        UUID firstRecipient = createUser("First Recipient");
        UUID secondRecipient = createUser("Second Recipient");
        TransferResult original = transferService.transfer(sender, firstRecipient, 100, "reused-key");

        mockMvc.perform(post("/transfers")
                        .with(jwt().jwt(token -> token.subject(sender.toString())))
                        .contentType("application/json")
                        .content("""
                                {"to_user":"%s","amount_paise":101,"idempotency_key":"reused-key"}
                                """.formatted(secondRecipient)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_conflict"));

        assertThat(balance(sender)).isEqualTo(900);
        assertThat(balance(firstRecipient)).isEqualTo(1100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Long.class)).isOne();
        assertThat(original.status()).isEqualTo(TransferStatus.APPLIED);
    }

    @Test
    void onlyParticipantsCanReadTransferAtHttpAndDatabaseBoundary() throws Exception {
        UUID sender = createUser("Sender");
        UUID recipient = createUser("Recipient");
        UUID stranger = createUser("Stranger");
        UUID transferId = transferService.transfer(sender, recipient, 25, "visible-key").transferId();

        mockMvc.perform(get("/transfers/{id}", transferId)
                        .with(jwt().jwt(token -> token.subject(sender.toString()))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/transfers/{id}", transferId)
                        .with(jwt().jwt(token -> token.subject(recipient.toString()))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/transfers/{id}", transferId)
                        .with(jwt().jwt(token -> token.subject(stranger.toString()))))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM transfers
                WHERE transfer_id = ? AND (? = from_user OR ? = to_user)
                """, Long.class, transferId, stranger, stranger)).isZero();
    }

    @Test
    void lockTimeoutIsReturnedAsRetryableServiceUnavailable() throws Exception {
        UUID sender = createUser("Sender");
        UUID recipient = createUser("Recipient");
        accountService.getOrCreate(sender);

        try (Connection lock = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT 1 FROM wallets WHERE user_id = ? FOR UPDATE")) {
                statement.setObject(1, sender);
                statement.executeQuery();
            }

            mockMvc.perform(post("/transfers")
                            .with(jwt().jwt(token -> token.subject(sender.toString())))
                            .contentType("application/json")
                            .content("""
                                    {"to_user":"%s","amount_paise":1,"idempotency_key":"locked-key"}
                                    """.formatted(recipient)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("database_temporarily_unavailable"))
                    .andExpect(jsonPath("$.retryable").value(true));
            lock.rollback();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Long.class)).isZero();
        assertThat(balance(sender)).isEqualTo(1000);
    }

    private UUID createUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (user_id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private String reverseOutcome(CountDownLatch start, UUID transferId, UUID sender) throws Exception {
        start.await();
        try {
            reversalService.reverse(transferId, sender, "shared-reversal-key");
            return "applied";
        } catch (IdempotencyConflictException exception) {
            return "conflict";
        }
    }

    private long balance(UUID user) {
        return jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, user);
    }

    private long count(String table, String column, UUID value) {
        if (!new HashSet<>(List.of("wallets", "transfers")).contains(table)
                || !new HashSet<>(List.of("user_id", "from_user")).contains(column)) {
            throw new IllegalArgumentException("Unexpected test identifier");
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
    }

    private <T> List<T> runTogether(int count, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        return getAll(futures);
    }

    private static <T> List<T> getAll(List<Future<T>> futures) throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }
}
