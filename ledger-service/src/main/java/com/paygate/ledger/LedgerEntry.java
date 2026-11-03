package com.paygate.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    public enum LedgerAccount {
        ACQUIRER_RECEIVABLE,
        MERCHANT_PAYABLE,
        PLATFORM_FEE,
        BANK_SETTLEMENT
    }

    public enum Direction {
        DEBIT,
        CREDIT
    }

    @Id
    private UUID id;

    @Column(name = "transaction_ref", nullable = false, updatable = false)
    private String transactionRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "account", nullable = false, updatable = false)
    private LedgerAccount account;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private Direction direction;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(String transactionRef, LedgerAccount account, UUID merchantId,
                       Direction direction, long amount, String currency) {
        this.id = UUID.randomUUID();
        this.transactionRef = transactionRef;
        this.account = account;
        this.merchantId = merchantId;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
