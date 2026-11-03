package com.paygate.payments.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse create(@Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.createPayment(
                request.merchantId(),
                request.amount(),
                request.currency(),
                request.customerEmail(),
                request.description());
        return PaymentResponse.from(payment);
    }

    @PostMapping("/{id}/pay")
    public PaymentResponse pay(@PathVariable("id") UUID id, @Valid @RequestBody PayRequest request) {
        Payment payment = paymentService.authorize(
                id,
                request.cardNumber(),
                request.expiry(),
                request.cvv(),
                request.cardholderName());
        return PaymentResponse.from(payment);
    }

    @PostMapping("/{id}/capture")
    public PaymentResponse capture(@PathVariable("id") UUID id) {
        return PaymentResponse.from(paymentService.capture(id));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable("id") UUID id) {
        return PaymentResponse.from(paymentService.get(id));
    }

    @GetMapping
    public List<PaymentResponse> list(@RequestParam(name = "merchantId", required = false) UUID merchantId) {
        return paymentService.list(merchantId).stream().map(PaymentResponse::from).toList();
    }

    public record CreatePaymentRequest(
            @NotNull UUID merchantId,
            @Positive long amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Email String customerEmail,
            @Size(max = 255) String description) {
    }

    public record PayRequest(
            @NotBlank String cardNumber,
            @NotBlank String expiry,
            @NotBlank String cvv,
            String cardholderName) {
    }

    public record PaymentResponse(
            UUID id,
            UUID merchantId,
            long amount,
            String currency,
            Payment.PaymentStatus status,
            String method,
            String cardLast4,
            String cardNetwork,
            Integer riskScore,
            String acquirerReference,
            String failureReason,
            String customerEmail,
            String description,
            Instant createdAt,
            Instant updatedAt) {

        static PaymentResponse from(Payment payment) {
            return new PaymentResponse(
                    payment.getId(),
                    payment.getMerchantId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    payment.getStatus(),
                    payment.getMethod(),
                    payment.getCardLast4(),
                    payment.getCardNetwork(),
                    payment.getRiskScore(),
                    payment.getAcquirerReference(),
                    payment.getFailureReason(),
                    payment.getCustomerEmail(),
                    payment.getDescription(),
                    payment.getCreatedAt(),
                    payment.getUpdatedAt());
        }
    }
}
