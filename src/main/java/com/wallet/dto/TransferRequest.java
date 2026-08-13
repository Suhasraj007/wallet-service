package com.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotBlank(message = "to_user is required")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$",
                message = "to_user must be 1-64 chars of [A-Za-z0-9_-]")
        String toUser,

        @NotNull(message = "amount_paise is required")
        @Positive(message = "amount_paise must be a positive integer")
        Long amountPaise,

        @NotBlank(message = "idempotency_key is required")
        @Pattern(regexp = "^[A-Za-z0-9_.:-]{1,128}$",
                message = "idempotency_key must be 1-128 chars of [A-Za-z0-9_.:-]")
        String idempotencyKey) {
}
