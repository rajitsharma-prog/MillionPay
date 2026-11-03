package com.paygate.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/postings")
    @ResponseStatus(HttpStatus.CREATED)
    public PostingResponse post(@Valid @RequestBody PostingRequest request) {
        List<LedgerEntry> entries = ledgerService.post(
                request.type(),
                request.transactionRef(),
                request.merchantId(),
                request.amount(),
                request.currency());
        return new PostingResponse(
                request.transactionRef(),
                entries.isEmpty(),
                entries.stream().map(EntryResponse::from).toList());
    }

    @GetMapping("/merchants/{merchantId}/balance")
    public BalanceResponse balance(@PathVariable("merchantId") UUID merchantId) {
        Map<LedgerEntry.LedgerAccount, Long> balances = new EnumMap<>(LedgerEntry.LedgerAccount.class);
        balances.putAll(ledgerService.balances(merchantId));
        long merchantPayable = balances.getOrDefault(LedgerEntry.LedgerAccount.MERCHANT_PAYABLE, 0L);
        return new BalanceResponse(merchantId, merchantPayable, balances);
    }

    @GetMapping("/merchants/{merchantId}/entries")
    public List<EntryResponse> entries(@PathVariable("merchantId") UUID merchantId) {
        return ledgerService.entries(merchantId).stream().map(EntryResponse::from).toList();
    }

    public record PostingRequest(
            @NotBlank String type,
            @NotBlank String transactionRef,
            @NotNull UUID merchantId,
            @Positive long amount,
            @NotBlank String currency) {
    }

    public record PostingResponse(
            String transactionRef,
            boolean duplicate,
            List<EntryResponse> entries) {
    }

    public record BalanceResponse(
            UUID merchantId,
            long merchantPayable,
            Map<LedgerEntry.LedgerAccount, Long> balances) {
    }

    public record EntryResponse(
            UUID id,
            String transactionRef,
            LedgerEntry.LedgerAccount account,
            UUID merchantId,
            LedgerEntry.Direction direction,
            long amount,
            String currency,
            Instant createdAt) {

        static EntryResponse from(LedgerEntry entry) {
            return new EntryResponse(
                    entry.getId(),
                    entry.getTransactionRef(),
                    entry.getAccount(),
                    entry.getMerchantId(),
                    entry.getDirection(),
                    entry.getAmount(),
                    entry.getCurrency(),
                    entry.getCreatedAt());
        }
    }
}
