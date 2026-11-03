package com.paygate.portal.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PlatformClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient paymentsClient;
    private final RestClient ledgerClient;
    private final RestClient settlementClient;
    private final RestClient riskClient;

    public PlatformClient(
            @Value("${services.payments.url:http://localhost:8081}") String paymentsUrl,
            @Value("${services.ledger.url:http://localhost:8082}") String ledgerUrl,
            @Value("${services.settlement.url:http://localhost:8083}") String settlementUrl,
            @Value("${services.risk.url:http://localhost:8084}") String riskUrl) {
        this.paymentsClient = RestClient.builder().baseUrl(paymentsUrl).build();
        this.ledgerClient = RestClient.builder().baseUrl(ledgerUrl).build();
        this.settlementClient = RestClient.builder().baseUrl(settlementUrl).build();
        this.riskClient = RestClient.builder().baseUrl(riskUrl).build();
    }

    public List<Map<String, Object>> listMerchants() {
        List<Map<String, Object>> result = execute(() -> paymentsClient.get()
                .uri("/api/v1/merchants")
                .retrieve()
                .body(LIST_TYPE));
        return result == null ? List.of() : result;
    }

    public Map<String, Object> createMerchant(String name, String email, String businessType) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("email", email);
        request.put("businessType", businessType);
        return execute(() -> paymentsClient.post()
                .uri("/api/v1/merchants")
                .body(request)
                .retrieve()
                .body(MAP_TYPE));
    }

    public Map<String, Object> rerunKyc(UUID merchantId) {
        return execute(() -> paymentsClient.post()
                .uri("/api/v1/merchants/{id}/kyc", merchantId)
                .retrieve()
                .body(MAP_TYPE));
    }

    public Map<String, Object> createPayment(UUID merchantId, long amount, String currency,
                                             String customerEmail, String description) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("merchantId", merchantId.toString());
        request.put("amount", amount);
        request.put("currency", currency);
        request.put("customerEmail", customerEmail);
        request.put("description", description);
        return execute(() -> paymentsClient.post()
                .uri("/api/v1/payments")
                .body(request)
                .retrieve()
                .body(MAP_TYPE));
    }

    public Map<String, Object> getPayment(UUID paymentId) {
        return execute(() -> paymentsClient.get()
                .uri("/api/v1/payments/{id}", paymentId)
                .retrieve()
                .body(MAP_TYPE));
    }

    public Map<String, Object> getMerchant(UUID merchantId) {
        return execute(() -> paymentsClient.get()
                .uri("/api/v1/merchants/{id}", merchantId)
                .retrieve()
                .body(MAP_TYPE));
    }

    public Map<String, Object> payPayment(UUID paymentId, String cardNumber, String expiry,
                                          String cvv, String cardholderName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("cardNumber", cardNumber);
        request.put("expiry", expiry);
        request.put("cvv", cvv);
        request.put("cardholderName", cardholderName);
        return execute(() -> paymentsClient.post()
                .uri("/api/v1/payments/{id}/pay", paymentId)
                .body(request)
                .retrieve()
                .body(MAP_TYPE));
    }

    public Map<String, Object> capturePayment(UUID paymentId) {
        return execute(() -> paymentsClient.post()
                .uri("/api/v1/payments/{id}/capture", paymentId)
                .retrieve()
                .body(MAP_TYPE));
    }

    public List<Map<String, Object>> listPayments(UUID merchantId) {
        List<Map<String, Object>> result = execute(() -> paymentsClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/v1/payments");
                    if (merchantId != null) {
                        uriBuilder.queryParam("merchantId", merchantId);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(LIST_TYPE));
        return result == null ? List.of() : result;
    }

    public Map<String, Object> getLedgerBalance(UUID merchantId) {
        return execute(() -> ledgerClient.get()
                .uri("/api/v1/ledger/merchants/{merchantId}/balance", merchantId)
                .retrieve()
                .body(MAP_TYPE));
    }

    public List<Map<String, Object>> listSettlements(UUID merchantId) {
        List<Map<String, Object>> result = execute(() -> settlementClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/v1/settlements");
                    if (merchantId != null) {
                        uriBuilder.queryParam("merchantId", merchantId);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(LIST_TYPE));
        return result == null ? List.of() : result;
    }

    public List<Map<String, Object>> runSettlement(UUID merchantId) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (merchantId != null) {
            request.put("merchantId", merchantId.toString());
        }
        List<Map<String, Object>> result = execute(() -> settlementClient.post()
                .uri("/api/v1/settlements/run")
                .body(request)
                .retrieve()
                .body(LIST_TYPE));
        return result == null ? List.of() : result;
    }

    public Map<String, Object> previewRisk(long amount, String currency, String cardCurrency,
                                           String cardFingerprint, String customerEmail) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", amount);
        request.put("currency", currency);
        request.put("cardCurrency", cardCurrency);
        request.put("cardFingerprint", cardFingerprint);
        request.put("customerEmail", customerEmail);
        return execute(() -> riskClient.post()
                .uri("/api/v1/risk/evaluate")
                .body(request)
                .retrieve()
                .body(MAP_TYPE));
    }

    private <T> T execute(BackendCall<T> call) {
        try {
            return call.invoke();
        } catch (RestClientResponseException ex) {
            throw new PlatformClientException(extractMessage(ex), ex.getStatusCode(), ex);
        } catch (RestClientException ex) {
            throw new PlatformClientException("Backend service is unavailable. Please try again shortly.",
                    null, ex);
        }
    }

    private String extractMessage(RestClientResponseException ex) {
        try {
            Map<String, Object> body = ex.getResponseBodyAs(MAP_TYPE);
            if (body != null) {
                Object message = body.get("message");
                if (message == null || message.toString().isBlank()) {
                    message = body.get("detail");
                }
                if (message == null || message.toString().isBlank()) {
                    message = body.get("error");
                }
                if (message != null && !message.toString().isBlank()) {
                    return message.toString();
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic message
        }
        String reason = ex.getStatusText();
        if (reason == null || reason.isBlank()) {
            return "Request failed with status " + ex.getStatusCode().value();
        }
        return reason;
    }

    @FunctionalInterface
    private interface BackendCall<T> {

        T invoke();
    }

    public static class PlatformClientException extends RuntimeException {

        private final transient HttpStatusCode statusCode;

        public PlatformClientException(String message, HttpStatusCode statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public HttpStatusCode getStatusCode() {
            return statusCode;
        }
    }
}
