package com.walletservice.repository;

import com.walletservice.exception.InvariantViolationException;
import com.walletservice.model.Transfer;
import com.walletservice.model.TransferStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores transfer state and implements the database half of the idempotency protocol.
 * A unique sender/idempotency-key constraint makes {@link #claim} safe under concurrency.
 */
@Repository
public class TransferRepository {

    private static final String CLAIM = """
            INSERT INTO transfers (
                transfer_id, from_user, to_user, amount_paise, idempotency_key, status
            ) VALUES (
                :transferId, :fromUser, :toUser, :amount, :idempotencyKey, 'PROCESSING'
            )
            ON CONFLICT (from_user, idempotency_key) DO NOTHING
            RETURNING transfer_id
            """;

    private static final String FIND_BY_SENDER_AND_KEY = """
            SELECT transfer_id, from_user, to_user, amount_paise, idempotency_key,
                   status, sender_balance_after, created_at
            FROM transfers
            WHERE from_user = :fromUser
              AND idempotency_key = :idempotencyKey
            """;

    private static final String FIND_VISIBLE_BY_ID = """
            SELECT transfer_id, from_user, to_user, amount_paise, idempotency_key,
                   status, sender_balance_after, created_at
            FROM transfers
            WHERE transfer_id = :transferId
              AND (:caller = from_user OR :caller = to_user)
              AND status <> 'PROCESSING'
            """;

    private static final String FINALIZE_APPLIED = """
            UPDATE transfers
            SET status = 'APPLIED', sender_balance_after = :senderBalanceAfter
            WHERE transfer_id = :transferId
              AND status = 'PROCESSING'
            """;

    private static final String FINALIZE_REJECTED = """
            UPDATE transfers
            SET status = 'REJECTED_INSUFFICIENT_FUNDS'
            WHERE transfer_id = :transferId
              AND status = 'PROCESSING'
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Creates a transfer repository backed by Spring's named-parameter JDBC template. */
    public TransferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Applies transaction-local PostgreSQL time limits so lock contention fails quickly and can be
     * surfaced to clients as a retryable response.
     */
    public void configureTransactionTimeouts() {
        jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '2s'");
        jdbc.getJdbcTemplate().execute("SET LOCAL statement_timeout = '4s'");
    }

    /**
     * Attempts to reserve an idempotency key with a new {@code PROCESSING} transfer row.
     *
     * @return the candidate ID when inserted, or empty when the sender/key already exists
     */
    public Optional<UUID> claim(
            UUID candidateTransferId,
            UUID fromUser,
            UUID toUser,
            long amount,
            String idempotencyKey
    ) {
        List<UUID> claimedIds = jdbc.query(
                CLAIM,
                new MapSqlParameterSource()
                        .addValue("transferId", candidateTransferId)
                        .addValue("fromUser", fromUser)
                        .addValue("toUser", toUser)
                        .addValue("amount", amount)
                        .addValue("idempotencyKey", idempotencyKey),
                (resultSet, rowNumber) -> resultSet.getObject("transfer_id", UUID.class)
        );
        return claimedIds.stream().findFirst();
    }

    /** Finds the transfer recorded for a sender-scoped idempotency key. */
    public Optional<Transfer> findBySenderAndKey(UUID fromUser, String idempotencyKey) {
        return queryOne(
                FIND_BY_SENDER_AND_KEY,
                new MapSqlParameterSource()
                        .addValue("fromUser", fromUser)
                        .addValue("idempotencyKey", idempotencyKey)
        );
    }

    /**
     * Finds a finalized transfer only if the caller participates as sender or recipient.
     * The visibility predicate is enforced in SQL to avoid accidentally exposing private data.
     */
    public Optional<Transfer> findVisibleById(UUID transferId, UUID caller) {
        return queryOne(
                FIND_VISIBLE_BY_ID,
                new MapSqlParameterSource()
                        .addValue("transferId", transferId)
                        .addValue("caller", caller)
        );
    }

    /** Transitions one claimed transfer from processing to applied and records its ending balance. */
    public void finalizeApplied(UUID transferId, long senderBalanceAfter) {
        assertSingleRow(jdbc.update(
                FINALIZE_APPLIED,
                new MapSqlParameterSource()
                        .addValue("transferId", transferId)
                        .addValue("senderBalanceAfter", senderBalanceAfter)
        ), "Applied transfer finalization");
    }

    /** Transitions one claimed transfer to the terminal insufficient-funds state. */
    public void finalizeRejected(UUID transferId) {
        assertSingleRow(jdbc.update(
                FINALIZE_REJECTED,
                new MapSqlParameterSource("transferId", transferId)
        ), "Rejected transfer finalization");
    }

    /** Executes a query expected to return at most one transfer. */
    private Optional<Transfer> queryOne(String sql, MapSqlParameterSource parameters) {
        List<Transfer> transfers = jdbc.query(sql, parameters, TransferRepository::mapTransfer);
        return transfers.stream().findFirst();
    }

    /** Fails fast when a state transition did not update exactly its claimed row. */
    private static void assertSingleRow(int affected, String operation) {
        if (affected != 1) {
            throw new InvariantViolationException(operation + " did not affect exactly one row");
        }
    }

    /** Maps the current JDBC row to the immutable transfer domain model. */
    private static Transfer mapTransfer(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Transfer(
                resultSet.getObject("transfer_id", UUID.class),
                resultSet.getObject("from_user", UUID.class),
                resultSet.getObject("to_user", UUID.class),
                resultSet.getLong("amount_paise"),
                resultSet.getString("idempotency_key"),
                TransferStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("sender_balance_after", Long.class),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
