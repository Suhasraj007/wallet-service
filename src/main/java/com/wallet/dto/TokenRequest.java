package com.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TokenRequest(
        @NotBlank(message = "user_id is required")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$",
                message = "user_id must be 1-64 chars of [A-Za-z0-9_-]")
        String userId) {
}
