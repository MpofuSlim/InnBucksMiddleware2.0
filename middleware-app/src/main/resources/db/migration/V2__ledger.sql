-- Local money-movement ledger: the write-ahead record for every core-banking
-- write, plus its journalled status history. The PENDING row commits BEFORE
-- the core call goes out, so an upstream success can never exist without a
-- local row, and an ambiguous outcome (status UNKNOWN) is always
-- reconcilable by external_ref. UNKNOWN rows are parked for the
-- reconciler — never guessed, never auto-expired.

CREATE TABLE ledger_transaction (
    id                  UUID                     PRIMARY KEY,
    customer_id         UUID                     NOT NULL REFERENCES customer (id),
    type                VARCHAR(16)              NOT NULL,  -- DEPOSIT / WITHDRAWAL / TRANSFER
    -- Core-side account externalIds. Source NULL for DEPOSIT, destination
    -- NULL for WITHDRAWAL.
    source_account      VARCHAR(64),
    destination_account VARCHAR(64),
    -- Minor units (MinorUnits type) — the only money representation that
    -- crosses the CoreBankingPort.
    amount_minor        BIGINT                   NOT NULL CHECK (amount_minor >= 0),
    currency            VARCHAR(3)               NOT NULL,
    narrative           VARCHAR(255),
    -- Middleware-minted reference sent to the core with the write; the
    -- reconciliation handle. Persisted before the call.
    external_ref        VARCHAR(64)              NOT NULL,
    core_provider       VARCHAR(16)              NOT NULL,
    -- The core's own reference, when echoed back.
    core_tx_ref         VARCHAR(128),
    -- PENDING / SUBMITTED / COMPLETED / FAILED / UNKNOWN (LedgerStatus).
    status              VARCHAR(24)              NOT NULL,
    failure_code        VARCHAR(64),
    failure_message     VARCHAR(500),
    correlation_id      VARCHAR(64),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMP WITH TIME ZONE,
    reconcile_attempts  INTEGER                  NOT NULL DEFAULT 0,
    next_reconcile_at   TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX uq_ledger_transaction_external_ref ON ledger_transaction (external_ref);
CREATE INDEX idx_ledger_transaction_customer ON ledger_transaction (customer_id, created_at DESC);
-- Serves both the due-for-reconciliation scan (UNKNOWN/SUBMITTED by deadline)
-- and the stale-PENDING sweep.
CREATE INDEX idx_ledger_transaction_status ON ledger_transaction (status, next_reconcile_at, created_at);

-- Append-only status history: one row per transition, written in the SAME
-- transaction as the status change so ledger and history cannot diverge.
-- (Tamper-evidence for money-significant transitions lives in audit_event's
-- HMAC chain; this journal is the operator's plain-text breadcrumb trail.)
CREATE TABLE ledger_transaction_event (
    id              BIGSERIAL                PRIMARY KEY,
    transaction_id  UUID                     NOT NULL REFERENCES ledger_transaction (id),
    from_status     VARCHAR(24),
    to_status       VARCHAR(24)              NOT NULL,
    detail          VARCHAR(500),
    correlation_id  VARCHAR(64),
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_transaction_event_txn ON ledger_transaction_event (transaction_id, occurred_at);
