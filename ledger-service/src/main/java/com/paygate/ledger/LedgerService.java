package com.paygate.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerService {

    public static final String PAYMENT_CAPTURE = "PAYMENT_CAPTURE";
    public static final String SETTLEMENT_PAYOUT = "SETTLEMENT_PAYOUT";

    private static final long BPS_DIVISOR = 10_000L;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final long feeBps;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository,
                         @Value("${ledger.fee-bps:200}") long feeBps) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.feeBps = feeBps;
    }

    @Transactional
    public List<LedgerEntry> post(String type, String transactionRef, UUID merchantId, long amount, String currency) {
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Posting type is required");
        }
        if (transactionRef == null || transactionRef.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transaction reference is required");
        }
        if (merchantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merchant id is required");
        }
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Currency is required");
        }

        if (ledgerEntryRepository.existsByTransactionRef(transactionRef)) {
            return List.of();
        }

        List<LedgerEntry> entries = switch (type.trim().toUpperCase()) {
            case PAYMENT_CAPTURE -> buildPaymentCapture(transactionRef, merchantId, amount, currency);
            case SETTLEMENT_PAYOUT -> buildSettlementPayout(transactionRef, merchantId, amount, currency);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported posting type: " + type);
        };

        assertBalanced(entries);
        return ledgerEntryRepository.saveAll(entries);
    }

    @Transactional(readOnly = true)
    public Map<LedgerEntry.LedgerAccount, Long> balances(UUID merchantId) {
        Map<LedgerEntry.LedgerAccount, Long> balances = new EnumMap<>(LedgerEntry.LedgerAccount.class);
        for (LedgerEntry.LedgerAccount account : LedgerEntry.LedgerAccount.values()) {
            balances.put(account, 0L);
        }
        for (LedgerEntryRepository.AccountBalance row : ledgerEntryRepository.sumBalancesByAccount(merchantId)) {
            balances.put(row.getAccount(), row.getBalance());
        }
        return balances;
    }

    @Transactional(readOnly = true)
    public long merchantPayableBalance(UUID merchantId) {
        return ledgerEntryRepository.sumCreditBalance(merchantId, LedgerEntry.LedgerAccount.MERCHANT_PAYABLE);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entries(UUID merchantId) {
        return ledgerEntryRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    private List<LedgerEntry> buildPaymentCapture(String transactionRef, UUID merchantId, long amount, String currency) {
        long fee = Math.floorDiv(Math.multiplyExact(amount, feeBps), BPS_DIVISOR);
        if (fee > amount) {
            fee = amount;
        }
        long net = amount - fee;

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(transactionRef, LedgerEntry.LedgerAccount.ACQUIRER_RECEIVABLE, merchantId,
                LedgerEntry.Direction.DEBIT, amount, currency));
        if (fee > 0) {
            entries.add(new LedgerEntry(transactionRef, LedgerEntry.LedgerAccount.PLATFORM_FEE, merchantId,
                    LedgerEntry.Direction.CREDIT, fee, currency));
        }
        if (net > 0) {
            entries.add(new LedgerEntry(transactionRef, LedgerEntry.LedgerAccount.MERCHANT_PAYABLE, merchantId,
                    LedgerEntry.Direction.CREDIT, net, currency));
        }
        return entries;
    }

    private List<LedgerEntry> buildSettlementPayout(String transactionRef, UUID merchantId, long amount, String currency) {
        long payable = merchantPayableBalance(merchantId);
        if (amount > payable) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Payout amount exceeds merchant payable balance");
        }

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(transactionRef, LedgerEntry.LedgerAccount.MERCHANT_PAYABLE, merchantId,
                LedgerEntry.Direction.DEBIT, amount, currency));
        entries.add(new LedgerEntry(transactionRef, LedgerEntry.LedgerAccount.BANK_SETTLEMENT, merchantId,
                LedgerEntry.Direction.CREDIT, amount, currency));
        return entries;
    }

    private void assertBalanced(List<LedgerEntry> entries) {
        long debits = 0;
        long credits = 0;
        for (LedgerEntry entry : entries) {
            if (entry.getDirection() == LedgerEntry.Direction.DEBIT) {
                debits += entry.getAmount();
            } else {
                credits += entry.getAmount();
            }
        }
        if (debits != credits) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unbalanced posting: debits " + debits + " do not equal credits " + credits);
        }
    }
}
