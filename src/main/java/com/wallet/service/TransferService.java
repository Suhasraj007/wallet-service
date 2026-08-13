package com.wallet.service;

import com.wallet.exception.IdempotencyConflictException;
import com.wallet.exception.InsufficientFundsException;
import com.wallet.exception.SelfTransferException;
import com.wallet.exception.TransferNotFoundException;
import com.wallet.metrics.WalletMetrics;
import com.wallet.model.TransferRecord;
import com.wallet.model.TransferStatus;
import com.wallet.repository.TransferRepository;
import com.wallet.repository.WalletRepository;
import com.wallet.util.RequestHash;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The core money-movement logic. One transaction, four steps, in an order
 * that was chosen for provable reasons:
 *
 * <p>1. Get-or-create BOTH wallets with ON CONFLICT DO NOTHING, in sorted
 *    user-id order. The unique index makes creation exactly-once with no
 *    exception; the sorted order prevents insert-vs-insert deadlocks.
 *
 * <p>2. Lock both wallet rows with SELECT ... FOR UPDATE, again in sorted
 *    order. All writers touching a pair serialize here, in one global order,
 *    so crossed concurrent transfers (A->B and B->A) cannot deadlock.
 *
 * <p>3. Only now, insert the transfer row. Its UNIQUE (from_user,
 *    idempotency_key) is the idempotency arbiter. Insert-after-lock matters:
 *    the naive order (insert first, lock after) makes the transfer row's FK
 *    take a KEY SHARE lock on the wallet rows, and the later FOR UPDATE
 *    becomes a lock upgrade across transactions - measured at a 38/40
 *    deadlock rate under a 40-way concurrent burst. With locks first, the FK
 *    check happens under locks we already hold.
 *
 * <p>4. Check funds and either apply both balance updates or record the
 *    rejection. Either way the outcome row commits atomically with the
 *    mutation, so a retry replays the exact original outcome.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransactionTemplate tx;
    private final WalletRepository wallets;
    private final TransferRepository transfers;
    private final WalletMetrics metrics;
    private final long seedBalancePaise;

    public TransferService(PlatformTransactionManager transactionManager,
                           WalletRepository wallets,
                           TransferRepository transfers,
                           WalletMetrics metrics,
                           @Value("${wallet.seed-balance-paise}") long seedBalancePaise) {
        this.tx = new TransactionTemplate(transactionManager);
        this.wallets = wallets;
        this.transfers = transfers;
        this.metrics = metrics;
        this.seedBalancePaise = seedBalancePaise;
    }

    public TransferResult transfer(String caller, String toUser, long amountPaise,
                                   String idempotencyKey) {
        if (caller.equals(toUser)) {
            throw new SelfTransferException();
        }
        String hash = RequestHash.of(toUser, amountPaise);

        // Fast path: if this (caller, key) already has a committed outcome,
        // serve it without touching any row locks.
        Optional<TransferRecord> existing = transfers.findByCallerAndKey(caller, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        TxOutcome outcome = tx.execute(status ->
                runTransferTx(caller, toUser, amountPaise, idempotencyKey, hash));

        // Wallet creation is reported only now, after commit. Counting it
        // inside the transaction would overstate reality on any rollback:
        // the metric and the log line would claim a wallet that does not
        // exist. Everything below is true of committed state only.
        for (String userId : outcome.createdWallets()) {
            metrics.walletCreated();
            log.atInfo().setMessage("wallet_created")
                    .addKeyValue("user_id", userId)
                    .addKeyValue("seed_balance_paise", seedBalancePaise)
                    .addKeyValue("during", "transfer")
                    .log();
        }

        if (outcome.keyTaken()) {
            // We lost the key race while waiting on locks; the winner has
            // committed (we held the same row locks), so its outcome is
            // committed and readable now.
            TransferRecord winner = transfers.findByCallerAndKey(caller, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency key taken but no committed row found"));
            return replay(winner, hash);
        }

        if (!outcome.applied()) {
            metrics.transferRejectedInsufficientFunds();
            log.atInfo().setMessage("transfer_rejected_insufficient_funds")
                    .addKeyValue("transfer_id", outcome.id())
                    .addKeyValue("from_user", caller)
                    .addKeyValue("to_user", toUser)
                    .addKeyValue("amount_paise", amountPaise)
                    .addKeyValue("balance_paise", outcome.fromBalanceAfter())
                    .log();
            throw new InsufficientFundsException(outcome.id(), outcome.fromBalanceAfter());
        }

        metrics.transferApplied();
        log.atInfo().setMessage("transfer_applied")
                .addKeyValue("transfer_id", outcome.id())
                .addKeyValue("from_user", caller)
                .addKeyValue("to_user", toUser)
                .addKeyValue("amount_paise", amountPaise)
                .addKeyValue("new_balance_paise", outcome.fromBalanceAfter())
                .log();
        return new TransferResult(outcome.id(), outcome.fromBalanceAfter(), false);
    }

    private TxOutcome runTransferTx(String from, String to, long amountPaise,
                                    String idempotencyKey, String hash) {
        String lo = from.compareTo(to) < 0 ? from : to;
        String hi = lo.equals(from) ? to : from;

        // 1. race-free get-or-create, sorted order
        List<String> created = wallets.getOrCreateSeeded(List.of(lo, hi), seedBalancePaise);

        // 2. lock both rows, sorted order
        long loBalance = wallets.lockBalance(lo);
        long hiBalance = wallets.lockBalance(hi);
        long fromBalance = from.equals(lo) ? loBalance : hiBalance;

        // 3. decide the outcome, then let the unique index arbitrate the key
        boolean applied = fromBalance >= amountPaise;
        long fromBalanceAfter = applied ? fromBalance - amountPaise : fromBalance;
        TransferStatus status = applied
                ? TransferStatus.APPLIED
                : TransferStatus.REJECTED_INSUFFICIENT_FUNDS;
        Optional<UUID> id = transfers.insertOutcome(
                from, to, amountPaise, idempotencyKey, hash, status, fromBalanceAfter);
        if (id.isEmpty()) {
            return TxOutcome.lostKeyRace(created);
        }

        // 4. mutate balances only for an applied transfer
        if (applied) {
            wallets.adjust(from, -amountPaise);
            wallets.adjust(to, amountPaise);
        }
        return new TxOutcome(id.get(), applied, fromBalanceAfter, false, created);
    }

    /** Serves the stored original outcome for a retried idempotency key. */
    private TransferResult replay(TransferRecord original, String hash) {
        if (!original.requestHash().equals(hash)) {
            metrics.idempotencyConflict();
            log.atInfo().setMessage("idempotency_conflict")
                    .addKeyValue("transfer_id", original.id())
                    .addKeyValue("from_user", original.fromUser())
                    .log();
            throw new IdempotencyConflictException();
        }
        metrics.idempotentReplay();
        log.atInfo().setMessage("idempotent_replay")
                .addKeyValue("transfer_id", original.id())
                .addKeyValue("original_status", original.status().name())
                .log();
        if (original.status() == TransferStatus.APPLIED) {
            return new TransferResult(original.id(), original.fromBalanceAfter(), true);
        }
        throw new InsufficientFundsException(original.id(), original.fromBalanceAfter());
    }

    /** Participant-only read; non-participants get the same 404 as a missing id. */
    public TransferRecord details(String caller, UUID id) {
        TransferRecord transfer = transfers.findById(id)
                .orElseThrow(TransferNotFoundException::new);
        if (!transfer.fromUser().equals(caller) && !transfer.toUser().equals(caller)) {
            throw new TransferNotFoundException();
        }
        return transfer;
    }

    /**
     * What the committed transaction actually did. createdWallets is carried
     * out so creation is reported after commit rather than during.
     */
    private record TxOutcome(UUID id, boolean applied, long fromBalanceAfter,
                             boolean keyTaken, List<String> createdWallets) {

        /**
         * A concurrent twin claimed the idempotency key first, so no transfer
         * row and no balance change came from this attempt. Any wallets this
         * transaction created still commit, so they are carried out.
         */
        static TxOutcome lostKeyRace(List<String> createdWallets) {
            return new TxOutcome(null, false, 0L, true, createdWallets);
        }
    }
}
