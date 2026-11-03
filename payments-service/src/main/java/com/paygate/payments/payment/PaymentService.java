package com.paygate.payments.payment;

import com.paygate.payments.merchant.Merchant;
import com.paygate.payments.merchant.MerchantService;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final PaymentRepository paymentRepository;
    private final MerchantService merchantService;
    private final RestClient riskClient;
    private final RestClient ledgerClient;
    private final RestClient partnerClient;

    public PaymentService(PaymentRepository paymentRepository,
                          MerchantService merchantService,
                          RestClient riskClient,
                          RestClient ledgerClient,
                          RestClient partnerClient) {
        this.paymentRepository = paymentRepository;
        this.merchantService = merchantService;
        this.riskClient = riskClient;
        this.ledgerClient = ledgerClient;
        this.partnerClient = partnerClient;
    }

    @Transactional
    public Payment createPayment(UUID merchantId, long amount, String currency, String customerEmail, String description) {
        Merchant merchant = merchantService.get(merchantId);
        if (merchant.getKycStatus() != Merchant.KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Merchant is not KYC verified: " + merchantId);
        }
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
        Payment payment = new Payment(merchantId, amount, currency, customerEmail, description);
        payment.setId(UUID.randomUUID());
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment authorize(UUID paymentId, String cardNumber, String expiry, String cvv, String cardholderName) {
        Payment payment = get(paymentId);
        if (payment.getStatus() != Payment.PaymentStatus.CREATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment cannot be authorized in status " + payment.getStatus());
        }
        if (cardNumber == null || cardNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Card number is required");
        }

        String sanitizedCard = cardNumber.replaceAll("\\s|-", "");
        String last4 = sanitizedCard.length() >= 4
                ? sanitizedCard.substring(sanitizedCard.length() - 4)
                : sanitizedCard;

        Map<String, Object> riskRequest = new LinkedHashMap<>();
        riskRequest.put("paymentId", payment.getId().toString());
        riskRequest.put("merchantId", payment.getMerchantId().toString());
        riskRequest.put("amount", payment.getAmount());
        riskRequest.put("currency", payment.getCurrency());
        riskRequest.put("customerEmail", payment.getCustomerEmail());
        riskRequest.put("cardFingerprint", fingerprint(sanitizedCard));
        riskRequest.put("cardCurrency", payment.getCurrency());

        Map<String, Object> riskResponse;
        try {
            riskResponse = riskClient.post()
                    .uri("/api/v1/risk/evaluate")
                    .body(riskRequest)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Risk service is unavailable", ex);
        }
        if (riskResponse == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Risk service returned an empty response");
        }

        Object score = riskResponse.get("score");
        if (score instanceof Number number) {
            payment.setRiskScore(number.intValue());
        }
        Object decision = riskResponse.get("decision");
        if (decision != null && "REJECT".equalsIgnoreCase(decision.toString())) {
            payment.setStatus(Payment.PaymentStatus.BLOCKED);
            payment.setFailureReason("Blocked by risk rules");
            payment.setCardLast4(last4);
            return paymentRepository.save(payment);
        }

        Map<String, Object> authRequest = new LinkedHashMap<>();
        authRequest.put("paymentId", payment.getId().toString());
        authRequest.put("amount", payment.getAmount());
        authRequest.put("currency", payment.getCurrency());
        authRequest.put("cardNumber", sanitizedCard);
        authRequest.put("expiry", expiry);
        authRequest.put("cvv", cvv);
        authRequest.put("cardholderName", cardholderName);

        Map<String, Object> authResponse;
        try {
            authResponse = partnerClient.post()
                    .uri("/sandbox/acquirer/authorize")
                    .body(authRequest)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Acquirer is unavailable", ex);
        }
        if (authResponse == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Acquirer returned an empty response");
        }

        payment.setMethod("CARD");
        payment.setCardLast4(last4);
        Object network = authResponse.get("network");
        if (network == null) {
            network = authResponse.get("cardNetwork");
        }
        payment.setCardNetwork(network == null ? null : network.toString());

        if (isApproved(authResponse)) {
            Object reference = authResponse.get("authorizationId");
            if (reference == null) {
                reference = authResponse.get("reference");
            }
            payment.setAcquirerReference(reference == null ? null : reference.toString());
            payment.setStatus(Payment.PaymentStatus.AUTHORIZED);
            payment.setFailureReason(null);
        } else {
            Object reason = authResponse.get("reason");
            if (reason == null) {
                reason = authResponse.get("message");
            }
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setFailureReason(reason == null ? "Card declined" : reason.toString());
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment capture(UUID paymentId) {
        Payment payment = get(paymentId);
        if (payment.getStatus() == Payment.PaymentStatus.CAPTURED) {
            return payment;
        }
        if (payment.getStatus() != Payment.PaymentStatus.AUTHORIZED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment cannot be captured in status " + payment.getStatus());
        }

        Map<String, Object> captureRequest = new LinkedHashMap<>();
        captureRequest.put("authorizationId", payment.getAcquirerReference());
        captureRequest.put("paymentId", payment.getId().toString());
        captureRequest.put("amount", payment.getAmount());
        captureRequest.put("currency", payment.getCurrency());

        Map<String, Object> captureResponse;
        try {
            captureResponse = partnerClient.post()
                    .uri("/sandbox/acquirer/capture")
                    .body(captureRequest)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Acquirer is unavailable", ex);
        }
        if (captureResponse == null || !isApproved(captureResponse)) {
            Object reason = captureResponse == null ? null : captureResponse.get("reason");
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setFailureReason(reason == null ? "Capture failed" : reason.toString());
            return paymentRepository.save(payment);
        }

        Map<String, Object> posting = new LinkedHashMap<>();
        posting.put("type", "PAYMENT_CAPTURE");
        posting.put("transactionRef", payment.getId().toString());
        posting.put("merchantId", payment.getMerchantId().toString());
        posting.put("amount", payment.getAmount());
        posting.put("currency", payment.getCurrency());

        try {
            ledgerClient.post()
                    .uri("/api/v1/ledger/postings")
                    .body(posting)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ledger service is unavailable", ex);
        }

        payment.setStatus(Payment.PaymentStatus.CAPTURED);
        payment.setFailureReason(null);
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Payment> list(UUID merchantId) {
        if (merchantId == null) {
            return paymentRepository.findAllByOrderByCreatedAtDesc();
        }
        return paymentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    private boolean isApproved(Map<String, Object> response) {
        Object approved = response.get("approved");
        if (approved instanceof Boolean flag) {
            return flag;
        }
        Object captured = response.get("captured");
        if (captured instanceof Boolean flag) {
            return flag;
        }
        Object status = response.get("status");
        if (status != null) {
            String value = status.toString().trim().toUpperCase();
            return "APPROVED".equals(value) || "AUTHORIZED".equals(value) || "CAPTURED".equals(value)
                    || "SUCCESS".equals(value);
        }
        return false;
    }

    private String fingerprint(String cardNumber) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cardNumber.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
