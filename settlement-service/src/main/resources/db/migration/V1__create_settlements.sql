CREATE TABLE settlements (
    id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    bank_reference VARCHAR(255),
    failure_reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_settlements PRIMARY KEY (id),
    CONSTRAINT chk_settlements_status CHECK (status IN ('INITIATED', 'PAID', 'FAILED')),
    CONSTRAINT chk_settlements_amount CHECK (amount > 0)
);

CREATE INDEX idx_settlements_merchant_created ON settlements (merchant_id, created_at);
