-- Wallets: one row per user. The PRIMARY KEY is what makes get-or-create
-- race-free: INSERT ... ON CONFLICT (user_id) DO NOTHING can never create
-- two rows and never throws, no matter how many requests race.
CREATE TABLE wallets (
    user_id       TEXT        PRIMARY KEY,
    balance_paise BIGINT      NOT NULL CHECK (balance_paise >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transfers: the ledger. Each row records one attempt's final outcome.
-- UNIQUE (from_user, idempotency_key) is the idempotency arbiter: the key is
-- scoped per caller, and the unique index decides exactly one winner per key.
-- from_balance_after stores the caller's balance in the original outcome so a
-- replay can return byte-identical results.
CREATE TABLE transfers (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    from_user          TEXT        NOT NULL REFERENCES wallets (user_id),
    to_user            TEXT        NOT NULL REFERENCES wallets (user_id),
    amount_paise       BIGINT      NOT NULL CHECK (amount_paise > 0),
    idempotency_key    TEXT        NOT NULL,
    request_hash       TEXT        NOT NULL,
    status             TEXT        NOT NULL
        CHECK (status IN ('APPLIED', 'REJECTED_INSUFFICIENT_FUNDS')),
    from_balance_after BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_caller_key UNIQUE (from_user, idempotency_key),
    CONSTRAINT chk_no_self_transfer CHECK (from_user <> to_user)
);

-- For "transfers where I am the recipient" reads; the unique constraint above
-- already indexes the (from_user, idempotency_key) lookups.
CREATE INDEX idx_transfers_to_user ON transfers (to_user);
