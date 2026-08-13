package com.wallet.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferDetailsResponse(
        UUID transferId,
        String fromUser,
        String toUser,
        long amountPaise,
        String status,
        OffsetDateTime createdAt) {
}
