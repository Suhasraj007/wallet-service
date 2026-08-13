package com.wallet.service;

import java.util.UUID;

/** Outcome of an applied transfer, fresh or replayed from the stored original. */
public record TransferResult(UUID transferId, long newBalancePaise, boolean replay) {
}
