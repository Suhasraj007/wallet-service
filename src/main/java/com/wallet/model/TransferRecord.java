package com.wallet.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferRecord(
        UUID id,
        String fromUser,
        String toUser,
        long amountPaise,
        String idempotencyKey,
        String requestHash,
        TransferStatus status,
        long fromBalanceAfter,
        OffsetDateTime createdAt) {
}
