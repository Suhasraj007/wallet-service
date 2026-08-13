package com.wallet.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransferRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private Set<ConstraintViolation<TransferRequest>> validate(TransferRequest request) {
        return validator.validate(request);
    }

    @Test
    void validRequestPasses() {
        assertThat(validate(new TransferRequest("bob", 500L, "key-1"))).isEmpty();
    }

    @Test
    void missingAmountFails() {
        assertThat(validate(new TransferRequest("bob", null, "key-1"))).isNotEmpty();
    }

    @Test
    void zeroAmountFails() {
        assertThat(validate(new TransferRequest("bob", 0L, "key-1"))).isNotEmpty();
    }

    @Test
    void negativeAmountFails() {
        assertThat(validate(new TransferRequest("bob", -100L, "key-1"))).isNotEmpty();
    }

    @Test
    void idempotencyKeyWithSpacesFails() {
        assertThat(validate(new TransferRequest("bob", 500L, "has spaces"))).isNotEmpty();
    }

    @Test
    void overlongToUserFails() {
        String longUser = "u".repeat(65);
        assertThat(validate(new TransferRequest(longUser, 500L, "key-1"))).isNotEmpty();
    }
}
