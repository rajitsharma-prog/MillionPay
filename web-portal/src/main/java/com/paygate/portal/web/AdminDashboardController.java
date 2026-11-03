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
public class AdminDashboardController {

    private static final String DEFAULT_BUSINESS_TYPE = "OTHER";

    private final PlatformClient platformClient;

    public AdminDashboardController(PlatformClient platformClient) {
        this.platformClient = platformClient;
    }

    @GetMapping("/admin")
    public String dashboard(Model model) {
        List<Map<String, Object>> merchants = new ArrayList<>();
        List<Map<String, Object>> payments = new ArrayList<>();
        List<Map<String, Object>> settlements = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            merchants = new ArrayList<>(platformClient.listMerchants());
        } catch (PlatformClientException ex) {
            errors.add(ex.getMessage());
        }
        try {
            payments = new ArrayList<>(platformClient.listPayments(null));
        } catch (PlatformClientException ex) {
            errors.add(ex.getMessage());
        }
        try {
            settlements = new ArrayList<>(platformClient.listSettlements(null));
        } catch (PlatformClientException ex) {
            errors.add(ex.getMessage());
        }

        long verifiedCount = 0L;
        for (Map<String, Object> merchant : merchants) {
            if ("VERIFIED".equals(String.valueOf(merchant.get("kycStatus")))) {
                verifiedCount++;
            }
        }

        model.addAttribute("pageTitle", "Admin Overview");
        model.addAttribute("activeNav", "admin");
        model.addAttribute("merchants", merchants);
        model.addAttribute("payments", payments);
        model.addAttribute("settlements", settlements);
        model.addAttribute("merchantCount", merchants.size());
        model.addAttribute("verifiedCount", verifiedCount);
        model.addAttribute("paymentCount", payments.size());
        model.addAttribute("settlementCount", settlements.size());
        if (!errors.isEmpty()) {
            model.addAttribute("error", String.join(" ", errors));
        }
        return "admin/dashboard";
    }

    @PostMapping("/admin/merchants")
    public String onboardMerchant(@RequestParam("name") String name,
                                  @RequestParam("email") String email,
                                  @RequestParam(name = "businessType", required = false) String businessType,
                                  RedirectAttributes redirectAttributes) {
        if (name == null || name.isBlank() || email == null || email.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Merchant name and email are required.");
            return "redirect:/admin";
        }
        String resolvedBusinessType = businessType == null || businessType.isBlank()
                ? DEFAULT_BUSINESS_TYPE
                : businessType.trim();
        try {
            Map<String, Object> merchant = platformClient.createMerchant(name.trim(), email.trim(), resolvedBusinessType);
            String status = merchant == null ? null : String.valueOf(merchant.get("kycStatus"));
            if ("VERIFIED".equals(status)) {
                redirectAttributes.addFlashAttribute("success",
                        "Merchant onboarded and KYC verified.");
            } else if ("REJECTED".equals(status)) {
                redirectAttributes.addFlashAttribute("error",
                        "Merchant onboarded but KYC was rejected.");
            } else {
                redirectAttributes.addFlashAttribute("success",
                        "Merchant onboarded. KYC status: " + status + ".");
            }
        } catch (PlatformClientException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/merchants/{id}/kyc")
    public String rerunKyc(@PathVariable("id") UUID id, RedirectAttributes redirectAttributes) {
        try {
            Map<String, Object> merchant = platformClient.rerunKyc(id);
            String status = merchant == null ? null : String.valueOf(merchant.get("kycStatus"));
            if ("VERIFIED".equals(status)) {
                redirectAttributes.addFlashAttribute("success", "KYC verification succeeded.");
            } else if ("REJECTED".equals(status)) {
                redirectAttributes.addFlashAttribute("error", "KYC verification was rejected.");
            } else {
                redirectAttributes.addFlashAttribute("success", "KYC re-run complete. Status: " + status + ".");
            }
        } catch (PlatformClientException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/settlements/run")
    public String runSettlement(@RequestParam(name = "merchantId", required = false) String merchantIdParam,
                                RedirectAttributes redirectAttributes) {
        UUID merchantId = null;
        if (merchantIdParam != null && !merchantIdParam.isBlank()) {
            try {
                merchantId = UUID.fromString(merchantIdParam.trim());
            } catch (IllegalArgumentException ex) {
                redirectAttributes.addFlashAttribute("error", "Invalid merchant selected for settlement.");
                return "redirect:/admin";
            }
        }
        try {
            List<Map<String, Object>> results = platformClient.runSettlement(merchantId);
            if (results.isEmpty()) {
                redirectAttributes.addFlashAttribute("success",
                        "Settlement run complete. No payable balances to settle.");
            } else {
                long paid = 0L;
                long failed = 0L;
                for (Map<String, Object> settlement : results) {
                    String status = String.valueOf(settlement.get("status"));
                    if ("PAID".equals(status)) {
                        paid++;
                    } else if ("FAILED".equals(status)) {
                        failed++;
                    }
                }
                if (failed > 0) {
                    redirectAttributes.addFlashAttribute("error",
                            "Settlement run complete: " + paid + " paid, " + failed + " failed.");
                } else {
                    redirectAttributes.addFlashAttribute("success",
                            "Settlement run complete: " + paid + " settlement(s) paid.");
                }
            }
        } catch (PlatformClientException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin";
    }
}
