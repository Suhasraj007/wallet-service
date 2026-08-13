package com.wallet.dto;

import java.util.UUID;

public record TransferResponse(UUID transferId, String status, long newBalancePaise) {
}
