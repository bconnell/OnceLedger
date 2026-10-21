CREATE TABLE demo_accounts (
    account_id VARCHAR(32) PRIMARY KEY,
    balance_cents BIGINT NOT NULL CHECK (balance_cents >= 0)
);

CREATE TABLE idempotency_receipts (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_fingerprint CHAR(64) NOT NULL,
    transfer_id UUID NOT NULL UNIQUE,
    response_status INTEGER NOT NULL CHECK (response_status = 0 OR response_status IN (201, 422)),
    response_outcome VARCHAR(16),
    response_reason VARCHAR(32),
    amount_cents BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT completed_receipt CHECK (
        response_status = 0 OR
        (response_outcome IS NOT NULL AND amount_cents IS NOT NULL)
    )
);

CREATE TABLE ledger_entries (
    entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES idempotency_receipts (transfer_id),
    account_id VARCHAR(32) NOT NULL REFERENCES demo_accounts (account_id),
    delta_cents BIGINT NOT NULL CHECK (delta_cents <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_ledger_entries_transfer ON ledger_entries (transfer_id);
