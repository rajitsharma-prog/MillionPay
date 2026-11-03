package com.paygate.simulator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
@RequestMapping("/sandbox")
public class SimulatorController {

    private static final String APPROVED_CARD = "4111111111111111";
    private static final String DECLINED_CARD = "4000000000000002";
    private static final String FAILING_ACCOUNT_SUFFIX = "0000";
    private static final String KYC_REJECT_KEYWORD = "reject";

    private final long latencyMs;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, AuthorizationRecord> authorizations = new ConcurrentHashMap<>();

    public SimulatorController(@Value("${simulator.latency-ms:0}") long latencyMs) {
        this.latencyMs = latencyMs;
    }

    @PostMapping("/acquirer/authorize")
    public AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
        applyLatency();

        String cardNumber = sanitize(request.cardNumber());
        String network = detectNetwork(cardNumber);

        if (!isDigits(cardNumber) || !passesLuhn(cardNumber)) {
            return new AuthorizeResponse(false, "DECLINED", null, network, "Invalid card number");
        }
        if (DECLINED_CARD.equals(cardNumber)) {
            return new AuthorizeResponse(false, "DECLINED", null, network, "Card declined by issuer");
        }

        String authorizationId = "AUTH" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        authorizations.put(authorizationId, new AuthorizationRecord(request.amount(), request.currency(), false));
        return new AuthorizeResponse(true, "AUTHORIZED", authorizationId, network, null);
    }

    @PostMapping("/acquirer/capture")
    public CaptureResponse capture(@Valid @RequestBody CaptureRequest request) {
        applyLatency();

        AuthorizationRecord record = authorizations.get(request.authorizationId());
        if (record == null) {
            return new CaptureResponse(false, "FAILED", null, "Authorization not found");
        }
        if (request.amount() > record.amount()) {
            return new CaptureResponse(false, "FAILED", null, "Capture amount exceeds authorized amount");
        }

        AuthorizationRecord captured = new AuthorizationRecord(record.amount(), record.currency(), true);
        authorizations.put(request.authorizationId(), captured);
        String reference = "CAP" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        return new CaptureResponse(true, "CAPTURED", reference, null);
    }

    @PostMapping("/bank/payout")
    public PayoutResponse payout(@Valid @RequestBody PayoutRequest request) {
        applyLatency();

        String account = request.accountNumber() == null ? "" : request.accountNumber().trim();
        if (account.endsWith(FAILING_ACCOUNT_SUFFIX)) {
            return new PayoutResponse(false, "FAILED", null, "Beneficiary account rejected by bank");
        }
        return new PayoutResponse(true, "PAID", generateUtr(), null);
    }

    @PostMapping("/kyc/verify")
    public KycResponse verifyKyc(@Valid @RequestBody KycRequest request) {
        applyLatency();

        String businessName = request.businessName() == null ? "" : request.businessName();
        String reference = "KYC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        if (businessName.toLowerCase(Locale.ROOT).contains(KYC_REJECT_KEYWORD)) {
            return new KycResponse(false, "REJECTED", reference, "Business could not be verified");
        }
        return new KycResponse(true, "VERIFIED", reference, null);
    }

    private void applyLatency() {
        if (latencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String generateUtr() {
        StringBuilder builder = new StringBuilder("UTR");
        builder.append(System.currentTimeMillis() % 1_000_000_000L);
        for (int i = 0; i < 6; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }

    private String sanitize(String cardNumber) {
        if (cardNumber == null) {
            return "";
        }
        return cardNumber.replaceAll("[\\s-]", "");
    }

    private boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean passesLuhn(String cardNumber) {
        int sum = 0;
        boolean alternate = false;
        for (int i = cardNumber.length() - 1; i >= 0; i--) {
            int digit = cardNumber.charAt(i) - '0';
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private String detectNetwork(String cardNumber) {
        if (cardNumber.isEmpty()) {
            return "UNKNOWN";
        }
        if (cardNumber.startsWith("4")) {
            return "VISA";
        }
        if (cardNumber.length() >= 2) {
            int firstTwo = parseInt(cardNumber.substring(0, 2));
            if (firstTwo >= 51 && firstTwo <= 55) {
                return "MASTERCARD";
            }
            if (firstTwo == 34 || firstTwo == 37) {
                return "AMEX";
            }
            if (firstTwo == 60 || firstTwo == 65 || firstTwo == 81 || firstTwo == 82) {
                return "RUPAY";
            }
        }
        if (cardNumber.length() >= 4) {
            int firstFour = parseInt(cardNumber.substring(0, 4));
            if (firstFour >= 2221 && firstFour <= 2720) {
                return "MASTERCARD";
            }
        }
        return "UNKNOWN";
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private record AuthorizationRecord(long amount, String currency, boolean captured) {
    }

    public record AuthorizeRequest(
            String paymentId,
            @Positive long amount,
            @NotBlank String currency,
            @NotBlank String cardNumber,
            String expiry,
            String cvv,
            String cardholderName) {
    }

    public record AuthorizeResponse(
            boolean approved,
            String status,
            String authorizationId,
            String network,
            String reason) {
    }

    public record CaptureRequest(
            @NotBlank String authorizationId,
            String paymentId,
            @Positive long amount,
            String currency) {
    }

    public record CaptureResponse(
            boolean captured,
            String status,
            String reference,
            String reason) {
    }

    public record PayoutRequest(
            String settlementId,
            String merchantId,
            @Positive long amount,
            String currency,
            @NotNull String accountNumber) {
    }

    public record PayoutResponse(
            boolean success,
            String status,
            String bankReference,
            String reason) {
    }

    public record KycRequest(
            String merchantId,
            @NotBlank String businessName,
            String email,
            String businessType) {
    }

    public record KycResponse(
            boolean verified,
            String status,
            String reference,
            String reason) {
    }
}
