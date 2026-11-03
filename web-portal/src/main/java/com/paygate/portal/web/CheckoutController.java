package com.paygate.portal.web;

import com.paygate.portal.client.PlatformClient;
import com.paygate.portal.client.PlatformClient.PlatformClientException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Controller
public class CheckoutController {

    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_AUTHORIZED = "AUTHORIZED";
    private static final String STATUS_CAPTURED = "CAPTURED";

    private final PlatformClient platformClient;

    public CheckoutController(PlatformClient platformClient) {
        this.platformClient = platformClient;
    }

    @GetMapping("/checkout/{paymentId}")
    public String checkout(@PathVariable("paymentId") UUID paymentId, Model model) {
        Map<String, Object> payment;
        try {
            payment = platformClient.getPayment(paymentId);
        } catch (PlatformClientException ex) {
            return renderFailure(model, paymentId, null, "Unable to load this payment: " + ex.getMessage());
        }
        if (payment == null) {
            return renderFailure(model, paymentId, null, "This payment could not be found.");
        }

        String status = String.valueOf(payment.get("status"));
        if (!STATUS_CREATED.equals(status)) {
            return renderResult(model, payment, null);
        }

        populateCheckoutModel(model, paymentId, payment);
        return "checkout/checkout";
    }

    @PostMapping("/checkout/{paymentId}")
    public String submit(@PathVariable("paymentId") UUID paymentId,
                         @RequestParam(name = "cardNumber", required = false) String cardNumber,
                         @RequestParam(name = "expiry", required = false) String expiry,
                         @RequestParam(name = "cvv", required = false) String cvv,
                         @RequestParam(name = "cardholderName", required = false) String cardholderName,
                         Model model) {
        Map<String, Object> payment;
        try {
            payment = platformClient.getPayment(paymentId);
        } catch (PlatformClientException ex) {
            return renderFailure(model, paymentId, null, "Unable to load this payment: " + ex.getMessage());
        }
        if (payment == null) {
            return renderFailure(model, paymentId, null, "This payment could not be found.");
        }

        String currentStatus = String.valueOf(payment.get("status"));
        if (!STATUS_CREATED.equals(currentStatus)) {
            return renderResult(model, payment, null);
        }

        String sanitizedNumber = cardNumber == null ? "" : cardNumber.replaceAll("[\\s-]", "");
        if (sanitizedNumber.isBlank() || expiry == null || expiry.isBlank()
                || cvv == null || cvv.isBlank()
                || cardholderName == null || cardholderName.isBlank()) {
            populateCheckoutModel(model, paymentId, payment);
            model.addAttribute("error", "Please complete all card details to continue.");
            return "checkout/checkout";
        }

        Map<String, Object> result;
        try {
            result = platformClient.payPayment(paymentId, sanitizedNumber, expiry.trim(), cvv.trim(),
                    cardholderName.trim());
            String status = result == null ? null : String.valueOf(result.get("status"));
            if (STATUS_AUTHORIZED.equals(status)) {
                result = platformClient.capturePayment(paymentId);
            }
        } catch (PlatformClientException ex) {
            return renderFailure(model, paymentId, payment, ex.getMessage());
        }

        if (result == null) {
            return renderFailure(model, paymentId, payment, "The payment could not be processed.");
        }
        return renderResult(model, result, payment);
    }

    private String renderResult(Model model, Map<String, Object> payment, Map<String, Object> original) {
        String status = String.valueOf(payment.get("status"));
        boolean success = STATUS_CAPTURED.equals(status);

        String failureReason = null;
        if (!success) {
            Object reason = payment.get("failureReason");
            if (reason != null && !reason.toString().isBlank()) {
                failureReason = reason.toString();
            } else if (STATUS_AUTHORIZED.equals(status)) {
                failureReason = "The payment was authorized but could not be captured.";
            } else if (STATUS_CREATED.equals(status)) {
                failureReason = "The payment has not been completed.";
            } else {
                failureReason = "The payment could not be completed.";
            }
        }

        Map<String, Object> merchant = loadMerchant(payment.get("merchantId"));

        model.addAttribute("pageTitle", "Payment result");
        model.addAttribute("success", success);
        model.addAttribute("status", status);
        model.addAttribute("payment", payment);
        model.addAttribute("paymentId", payment.get("id"));
        model.addAttribute("amount", toLong(payment.get("amount")));
        model.addAttribute("currency", payment.get("currency"));
        model.addAttribute("cardNetwork", payment.get("cardNetwork"));
        model.addAttribute("cardLast4", payment.get("cardLast4"));
        model.addAttribute("acquirerReference", payment.get("acquirerReference"));
        model.addAttribute("failureReason", failureReason);
        model.addAttribute("merchantName", merchant == null ? null : merchant.get("name"));
        model.addAttribute("merchantId", payment.get("merchantId"));
        return "checkout/result";
    }

    private String renderFailure(Model model, UUID paymentId, Map<String, Object> payment, String message) {
        Map<String, Object> source = payment == null ? new LinkedHashMap<>() : payment;
        Map<String, Object> merchant = loadMerchant(source.get("merchantId"));

        model.addAttribute("pageTitle", "Payment result");
        model.addAttribute("success", false);
        model.addAttribute("status", "FAILED");
        model.addAttribute("payment", source);
        model.addAttribute("paymentId", paymentId);
        model.addAttribute("amount", toLong(source.get("amount")));
        model.addAttribute("currency", source.get("currency"));
        model.addAttribute("cardNetwork", null);
        model.addAttribute("cardLast4", null);
        model.addAttribute("acquirerReference", null);
        model.addAttribute("failureReason", message);
        model.addAttribute("merchantName", merchant == null ? null : merchant.get("name"));
        model.addAttribute("merchantId", source.get("merchantId"));
        return "checkout/result";
    }

    private void populateCheckoutModel(Model model, UUID paymentId, Map<String, Object> payment) {
        Map<String, Object> merchant = loadMerchant(payment.get("merchantId"));

        model.addAttribute("pageTitle", "Secure checkout");
        model.addAttribute("payment", payment);
        model.addAttribute("paymentId", paymentId);
        model.addAttribute("amount", toLong(payment.get("amount")));
        model.addAttribute("currency", payment.get("currency"));
        model.addAttribute("description", payment.get("description"));
        model.addAttribute("customerEmail", payment.get("customerEmail"));
        model.addAttribute("merchantName", merchant == null ? null : merchant.get("name"));
    }

    private Map<String, Object> loadMerchant(Object merchantIdValue) {
        if (merchantIdValue == null) {
            return null;
        }
        try {
            UUID merchantId = UUID.fromString(merchantIdValue.toString());
            return platformClient.getMerchant(merchantId);
        } catch (IllegalArgumentException | PlatformClientException ex) {
            return null;
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
