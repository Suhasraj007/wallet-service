package com.wallet.dto;

import java.util.UUID;

public record InsufficientFundsResponse(String error, UUID transferId, long balancePaise) {
}
