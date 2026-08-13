package com.wallet.service;

import com.wallet.dto.AccountResponse;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.metrics.WalletMetrics;
import com.wallet.repository.WalletRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final WalletRepository wallets;
    private final WalletMetrics metrics;
    private final long seedBalancePaise;

    public AccountService(WalletRepository wallets,
                          WalletMetrics metrics,
                          @Value("${wallet.seed-balance-paise}") long seedBalancePaise) {
        this.wallets = wallets;
        this.metrics = metrics;
        this.seedBalancePaise = seedBalancePaise;
    }

    /**
     * Idempotent get-or-create: the ON CONFLICT upsert guarantees calling this
     * twice (or a thousand times, concurrently) yields exactly one wallet row.
     */
    public AccountResponse getOrCreate(String userId) {
        boolean created = !wallets.getOrCreateSeeded(List.of(userId), seedBalancePaise).isEmpty();
        if (created) {
            metrics.walletCreated();
            log.atInfo().setMessage("wallet_created")
                    .addKeyValue("user_id", userId)
                    .addKeyValue("seed_balance_paise", seedBalancePaise)
                    .addKeyValue("during", "accounts")
                    .log();
        }
        long balance = wallets.balance(userId)
                .orElseThrow(() -> new IllegalStateException("wallet missing after upsert"));
        return new AccountResponse(userId, balance, created);
    }

    public AccountResponse me(String userId) {
        long balance = wallets.balance(userId).orElseThrow(WalletNotFoundException::new);
        return new AccountResponse(userId, balance, null);
    }
}
