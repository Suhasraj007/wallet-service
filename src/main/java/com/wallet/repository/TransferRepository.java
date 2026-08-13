package com.wallet.repository;

import com.wallet.model.TransferRecord;
import com.wallet.model.TransferStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private static final RowMapper<TransferRecord> MAPPER = (rs, rowNum) -> new TransferRecord(
            rs.getObject("id", UUID.class),
            rs.getString("from_user"),
            rs.getString("to_user"),
            rs.getLong("amount_paise"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getLong("from_balance_after"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TransferRecord> findByCallerAndKey(String fromUser, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM transfers "
                        + "WHERE from_user = :from AND idempotency_key = :key")
                .param("from", fromUser)
                .param("key", idempotencyKey)
                .query(MAPPER)
                .optional();
    }

    public Optional<TransferRecord> findById(UUID id) {
        return jdbc.sql("SELECT * FROM transfers WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Records the transfer's final outcome. UNIQUE (from_user, idempotency_key)
     * is the idempotency arbiter: exactly one insert per (caller, key) can ever
     * succeed. ON CONFLICT DO NOTHING turns "we lost the key race" into an
     * empty Optional instead of an exception - the caller then serves the
     * stored original outcome.
     */
    public Optional<UUID> insertOutcome(String fromUser,
                                        String toUser,
                                        long amountPaise,
                                        String idempotencyKey,
                                        String requestHash,
                                        TransferStatus status,
                                        long fromBalanceAfter) {
        return jdbc.sql("INSERT INTO transfers "
                        + "(from_user, to_user, amount_paise, idempotency_key, "
                        + "request_hash, status, from_balance_after) "
                        + "VALUES (:from, :to, :amount, :key, :hash, :status, :after) "
                        + "ON CONFLICT (from_user, idempotency_key) DO NOTHING "
                        + "RETURNING id")
                .param("from", fromUser)
                .param("to", toUser)
                .param("amount", amountPaise)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("status", status.name())
                .param("after", fromBalanceAfter)
                .query(UUID.class)
                .optional();
    }
}
