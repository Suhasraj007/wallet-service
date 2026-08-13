package com.wallet.dto;

public record AccountResponse(String userId, long balancePaise, Boolean created) {
}
