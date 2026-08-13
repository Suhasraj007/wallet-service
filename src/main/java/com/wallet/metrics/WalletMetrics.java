package com.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The business counters the brief asks for. Request count / latency / error
 * rate come from Spring's built-in http.server.requests timer (with
 * percentile histograms enabled), all exposed at GET /metrics.
 */
@Component
public class WalletMetrics {

    private final Counter walletsCreated;
    private final Counter transfersApplied;
    private final Counter transfersRejectedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter idempotencyConflicts;
    private final Counter authFailures;

    public WalletMetrics(MeterRegistry registry) {
        this.walletsCreated = Counter.builder("wallet.wallets.created")
                .description("Wallets created (get-or-create actually inserted)")
                .register(registry);
        this.transfersApplied = Counter.builder("wallet.transfers.applied")
                .description("Transfers applied (funds moved)")
                .register(registry);
        this.transfersRejectedInsufficientFunds = Counter.builder("wallet.transfers.rejected")
                .tag("reason", "insufficient_funds")
                .description("Transfers rejected")
                .register(registry);
        this.idempotentReplays = Counter.builder("wallet.idempotent.replays")
                .description("Retries answered from the stored original outcome")
                .register(registry);
        this.idempotencyConflicts = Counter.builder("wallet.idempotency.conflicts")
                .description("Same idempotency key reused with a different body (409)")
                .register(registry);
        this.authFailures = Counter.builder("wallet.auth.failures")
                .description("Requests rejected with 401")
                .register(registry);
    }

    public void walletCreated() {
        walletsCreated.increment();
    }

    public void transferApplied() {
        transfersApplied.increment();
    }

    public void transferRejectedInsufficientFunds() {
        transfersRejectedInsufficientFunds.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void idempotencyConflict() {
        idempotencyConflicts.increment();
    }

    public void authFailure() {
        authFailures.increment();
    }
}
