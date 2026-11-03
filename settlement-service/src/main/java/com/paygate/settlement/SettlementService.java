package com.paygate.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private static final String DEFAULT_CURRENCY = "INR";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final SettlementRepository settlementRepository;
    private final RestClient ledgerClient;
    private final RestClient paymentsClient;
    private final RestClient partnerClient;

    public SettlementService(SettlementRepository settlementRepository,
                             @Value("${services.ledger.url:http://localhost:8082}") String ledgerUrl,
                             @Value("${services.payments.url:http://localhost:8081}") String paymentsUrl,
                             @Value("${services.partner.url:http://localhost:8085}") String partnerUrl) {
        this.settlementRepository = settlementRepository;
        this.ledgerClient = RestClient.builder().baseUrl(ledgerUrl).build();
        this.paymentsClient = RestClient.builder().baseUrl(paymentsUrl).build();
        this.partnerClient = RestClient.builder().baseUrl(partnerUrl).build();
    }

    public List<Settlement> run(UUID merchantId) {
        List<UUID> merchantIds = new ArrayList<>();
        if (merchantId != null) {
            merchantIds.add(merchantId);
        } else {
            merchantIds.addAll(listMerchantIds());
        }

        List<Settlement> results = new ArrayList<>();
        for (UUID id : merchantIds) {
            Settlement settlement = settleMerchant(id);
            if (settlement != null) {
                results.add(settlement);
            }
        }
        return results;
    }

    public List<Settlement> list(UUID merchantId) {
        if (merchantId == null) {
            return settlementRepository.findAllByOrderByCreatedAtDesc();
        }
        return settlementRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    @Scheduled(cron = "${settlement.cron:0 0 2 * * *}")
    public void runScheduled() {
        try {
            List<Settlement> settlements = run(null);
            log.info("Scheduled settlement run completed with {} settlements", settlements.size());
        } catch (RuntimeException ex) {
            log.error("Scheduled settlement run failed: {}", ex.getMessage());
        }
    }

    private Settlement settleMerchant(UUID merchantId) {
        long payable;
        try {
            payable = fetchPayableBalance(merchantId);
        } catch (RuntimeException ex) {
            log.warn("Skipping merchant {}: unable to read payable balance: {}", merchantId, ex.getMessage());
            return null;
        }
        if (payable <= 0) {
            return null;
        }

        Settlement settlement = new Settlement(merchantId, payable, DEFAULT_CURRENCY);
        settlement = settlementRepository.save(settlement);

        Map<String, Object> payoutRequest = new LinkedHashMap<>();
        payoutRequest.put("settlementId", settlement.getId().toString());
        payoutRequest.put("merchantId", merchantId.toString());
        payoutRequest.put("amount", settlement.getAmount());
        payoutRequest.put("currency", settlement.getCurrency());
        payoutRequest.put("accountNumber", merchantAccountNumber(merchantId));

        Map<String, Object> payoutResponse;
        try {
            payoutResponse = partnerClient.post()
                    .uri("/sandbox/bank/payout")
                    .body(payoutRequest)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientException ex) {
            return fail(settlement, "Bank partner is unavailable");
        }

        if (payoutResponse == null || !isSuccessful(payoutResponse)) {
            Object reason = payoutResponse == null ? null : payoutResponse.get("reason");
            if (reason == null && payoutResponse != null) {
                reason = payoutResponse.get("message");
            }
            return fail(settlement, reason == null ? "Bank payout failed" : reason.toString());
        }

        Object reference = payoutResponse.get("bankReference");
        if (reference == null) {
            reference = payoutResponse.get("utr");
        }
        if (reference == null) {
            reference = payoutResponse.get("reference");
        }

        Map<String, Object> posting = new LinkedHashMap<>();
        posting.put("type", "SETTLEMENT_PAYOUT");
        posting.put("transactionRef", settlement.getId().toString());
        posting.put("merchantId", merchantId.toString());
        posting.put("amount", settlement.getAmount());
        posting.put("currency", settlement.getCurrency());

        try {
            ledgerClient.post()
                    .uri("/api/v1/ledger/postings")
                    .body(posting)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            settlement.setBankReference(reference == null ? null : reference.toString());
            return fail(settlement, "Ledger posting failed after bank payout");
        }

        settlement.setBankReference(reference == null ? null : reference.toString());
        settlement.setStatus(Settlement.SettlementStatus.PAID);
        settlement.setFailureReason(null);
        return settlementRepository.save(settlement);
    }

    private Settlement fail(Settlement settlement, String reason) {
        settlement.setStatus(Settlement.SettlementStatus.FAILED);
        settlement.setFailureReason(reason);
        return settlementRepository.save(settlement);
    }

    private long fetchPayableBalance(UUID merchantId) {
        Map<String, Object> response;
        try {
            response = ledgerClient.get()
                    .uri("/api/v1/ledger/merchants/{merchantId}/balance", merchantId)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ledger service is unavailable", ex);
        }
        if (response == null) {
            return 0L;
        }
        Object direct = response.get("merchantPayable");
        if (direct instanceof Number number) {
            return number.longValue();
        }
        Object balances = response.get("balances");
        if (balances instanceof Map<?, ?> map) {
            Object value = map.get("MERCHANT_PAYABLE");
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        Object account = response.get("MERCHANT_PAYABLE");
        if (account instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private List<UUID> listMerchantIds() {
        List<Map<String, Object>> merchants;
        try {
            merchants = paymentsClient.get()
                    .uri("/api/v1/merchants")
                    .retrieve()
                    .body(LIST_TYPE);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Payments service is unavailable", ex);
        }
        List<UUID> ids = new ArrayList<>();
        if (merchants == null) {
            return ids;
        }
        for (Map<String, Object> merchant : merchants) {
            Object id = merchant.get("id");
            if (id == null) {
                continue;
            }
            try {
                ids.add(UUID.fromString(id.toString()));
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring merchant with invalid id: {}", id);
            }
        }
        return ids;
    }

    private String merchantAccountNumber(UUID merchantId) {
        String digits = merchantId.toString().replaceAll("[^0-9]", "");
        StringBuilder builder = new StringBuilder(digits);
        while (builder.length() < 12) {
            builder.append('1');
        }
        String account = builder.substring(0, 12);
        if (account.endsWith("0000")) {
            account = account.substring(0, 8) + "1234";
        }
        return account;
    }

    private boolean isSuccessful(Map<String, Object> response) {
        Object success = response.get("success");
        if (success instanceof Boolean flag) {
            return flag;
        }
        Object approved = response.get("approved");
        if (approved instanceof Boolean flag) {
            return flag;
        }
        Object status = response.get("status");
        if (status != null) {
            String value = status.toString().trim().toUpperCase();
            return "SUCCESS".equals(value) || "PAID".equals(value) || "PROCESSED".equals(value)
                    || "COMPLETED".equals(value);
        }
        return false;
    }
}
