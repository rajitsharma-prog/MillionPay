CREATE TABLE ledger_entries (
    id UUID NOT NULL,
    transaction_ref VARCHAR(255) NOT NULL,
    account VARCHAR(32) NOT NULL,
    merchant_id UUID NOT NULL,
    direction VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT chk_ledger_entries_account CHECK (account IN ('ACQUIRER_RECEIVABLE', 'MERCHANT_PAYABLE', 'PLATFORM_FEE', 'BANK_SETTLEMENT')),
    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_amount CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_merchant_account ON ledger_entries (merchant_id, account);

CREATE INDEX idx_ledger_entries_transaction_ref ON ledger_entries (transaction_ref);
