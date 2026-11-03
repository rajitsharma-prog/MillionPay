package com.paygate.portal.web;

import com.paygate.portal.client.PlatformClient;
import com.paygate.portal.client.PlatformClient.PlatformClientException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class MerchantDashboardController {

    private static final String STATUS_CAPTURED = "CAPTURED";

    private final PlatformClient platformClient;

    public MerchantDashboardController(PlatformClient platformClient) {
        this.platformClient = platformClient;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/merchant";
    }

    @GetMapping("/merchant")
    public String dashboard(@RequestParam(name = "merchantId", required = false) String merchantIdParam,
                            Model model) {
        List<Map<String, Object>> merchants = new ArrayList<>();
        List<Map<String, Object>> payments = new ArrayList<>();
        List<Map<String, Object>> settlements = new ArrayList<>();
        Map<String, Object> selectedMerchant = null;
        long payableBalance = 0L;
        String errorMessage = null;

        try {
            merchants = new ArrayList<>(platformClient.listMerchants());
        } catch (PlatformClientException ex) {
            errorMessage = ex.getMessage();
        }

        UUID selectedId = resolveSelectedId(merchantIdParam, merchants);
        if (selectedId != null) {
            for (Map<String, Object> merchant : merchants) {
                if (selectedId.toString().equals(String.valueOf(merchant.get("id")))) {
                    selectedMerchant = merchant;
                    break;
                }
            }
            try {
                payments = new ArrayList<>(platformClient.listPayments(selectedId));
            } catch (PlatformClientException ex) {
                errorMessage = ex.getMessage();
            }
            try {
                settlements = new ArrayList<>(platformClient.listSettlements(selectedId));
            } catch (PlatformClientException ex) {
                errorMessage = ex.getMessage();
            }
            try {
                payableBalance = extractPayableBalance(platformClient.getLedgerBalance(selectedId));
            } catch (PlatformClientException ex) {
                errorMessage = ex.getMessage();
            }
        }

        long grossVolume = 0L;
        long capturedCount = 0L;
        for (Map<String, Object> payment : payments) {
            if (STATUS_CAPTURED.equals(String.valueOf(payment.get("status")))) {
                capturedCount++;
                grossVolume += toLong(payment.get("amount"));
            }
        }

        model.addAttribute("pageTitle", "Merchant Overview");
        model.addAttribute("activeNav", "merchant");
        model.addAttribute("merchants", merchants);
        model.addAttribute("selectedMerchant", selectedMerchant);
        model.addAttribute("selectedMerchantId", selectedId == null ? null : selectedId.toString());
        model.addAttribute("payments", payments);
        model.addAttribute("settlements", settlements);
        model.addAttribute("grossVolume", grossVolume);
        model.addAttribute("capturedCount", capturedCount);
        model.addAttribute("payableBalance", payableBalance);
        if (errorMessage != null) {
            model.addAttribute("error", errorMessage);
        }
        return "merchant/dashboard";
    }

    @PostMapping("/merchant/payments/{id}/capture")
    public String capture(@PathVariable("id") UUID id,
                          @RequestParam(name = "merchantId", required = false) String merchantId,
                          RedirectAttributes redirectAttributes) {
        try {
            Map<String, Object> result = platformClient.capturePayment(id);
            String status = result == null ? null : String.valueOf(result.get("status"));
            if (STATUS_CAPTURED.equals(status)) {
                redirectAttributes.addFlashAttribute("success", "Payment captured successfully.");
            } else {
                Object reason = result == null ? null : result.get("failureReason");
                redirectAttributes.addFlashAttribute("error",
                        reason == null ? "Capture could not be completed." : "Capture failed: " + reason);
            }
        } catch (PlatformClientException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        if (merchantId != null && !merchantId.isBlank()) {
            redirectAttributes.addAttribute("merchantId", merchantId);
        }
        return "redirect:/merchant";
    }

    private UUID resolveSelectedId(String merchantIdParam, List<Map<String, Object>> merchants) {
        if (merchantIdParam != null && !merchantIdParam.isBlank()) {
            try {
                return UUID.fromString(merchantIdParam.trim());
            } catch (IllegalArgumentException ignored) {
                // fall back to the first merchant
            }
        }
        if (!merchants.isEmpty()) {
            Object firstId = merchants.get(0).get("id");
            if (firstId != null) {
                try {
                    return UUID.fromString(firstId.toString());
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private long extractPayableBalance(Map<String, Object> balance) {
        if (balance == null) {
            return 0L;
        }
        Object direct = balance.get("MERCHANT_PAYABLE");
        if (direct != null) {
            return toLong(direct);
        }
        Object payable = balance.get("merchantPayable");
        if (payable != null) {
            return toLong(payable);
        }
        Object balances = balance.get("balances");
        if (balances instanceof Map<?, ?> nested) {
            Object value = ((Map<String, Object>) nested).get("MERCHANT_PAYABLE");
            if (value != null) {
                return toLong(value);
            }
        }
        return 0L;
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
