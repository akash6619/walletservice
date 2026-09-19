package com.walletservice.repository;

import com.walletservice.exception.InvariantViolationException;
import com.walletservice.model.Wallet;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Persists wallets and performs balance mutations as atomic SQL operations.
 * Monetary values are integer paise; no floating-point conversion occurs in this layer.
 */
@Repository
public class WalletRepository {

    private static final String CREATE_IF_ABSENT = """
            INSERT INTO wallets (wallet_id, user_id, balance_paise)
            VALUES (:walletId, :userId, :initialBalance)
            ON CONFLICT (user_id) DO NOTHING
            """;

    private static final String FIND_BY_USER_ID = """
            SELECT wallet_id, user_id, balance_paise, created_at, updated_at
            FROM wallets
            WHERE user_id = :userId
            """;

    private static final String DEBIT_IF_SUFFICIENT = """
            UPDATE wallets
            SET balance_paise = balance_paise - :amount,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId
              AND balance_paise >= :amount
            RETURNING balance_paise
            """;

    private static final String CREDIT = """
            UPDATE wallets
            SET balance_paise = balance_paise + :amount,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Creates a wallet repository backed by Spring's named-parameter JDBC template. */
    public WalletRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a wallet unless the user's unique wallet already exists.
     *
     * @return {@code true} only when this call inserted the row
     */
    public boolean createIfAbsent(UUID userId, long initialBalance) {
        int affected = jdbc.update(
                CREATE_IF_ABSENT,
                new MapSqlParameterSource()
                        .addValue("walletId", UUID.randomUUID())
                        .addValue("userId", userId)
                        .addValue("initialBalance", initialBalance)
        );
        if (affected < 0 || affected > 1) {
            throw new InvariantViolationException("Wallet upsert affected an unexpected number of rows");
        }
        return affected == 1;
    }

    /** Finds the wallet belonging to a user. */
    public Optional<Wallet> findByUserId(UUID userId) {
        List<Wallet> wallets = jdbc.query(
                FIND_BY_USER_ID,
                new MapSqlParameterSource("userId", userId),
                WalletRepository::mapWallet
        );
        return wallets.stream().findFirst();
    }

    /**
     * Atomically debits the requested amount only when the wallet has sufficient funds.
     * The returned value is the balance after the debit; an empty value means no row qualified.
     */
    public OptionalLong debitIfSufficient(UUID userId, long amount) {
        List<Long> balances = jdbc.query(
                DEBIT_IF_SUFFICIENT,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("amount", amount),
                (resultSet, rowNumber) -> resultSet.getLong("balance_paise")
        );
        return balances.isEmpty() ? OptionalLong.empty() : OptionalLong.of(balances.getFirst());
    }

    /** Credits exactly one recipient wallet, treating any other row count as corruption. */
    public void credit(UUID userId, long amount) {
        int affected = jdbc.update(
                CREDIT,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("amount", amount)
        );
        if (affected != 1) {
            throw new InvariantViolationException("Recipient credit did not affect exactly one wallet");
        }
    }

    /** Maps the current JDBC row to the immutable wallet domain model. */
    private static Wallet mapWallet(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Wallet(
                resultSet.getObject("wallet_id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getLong("balance_paise"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
