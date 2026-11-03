package com.paygate.portal.web;

import com.paygate.portal.client.PlatformClient;
import com.paygate.portal.client.PlatformClient.PlatformClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class DeveloperDashboardController {

    private static final String DEFAULT_CURRENCY = "INR";
    private static final String DEFAULT_DESCRIPTION = "Sandbox test payment";
    private static final String DEFAULT_CUSTOMER_EMAIL = "test.customer@example.com";

    private final PlatformClient platformClient;
    private final String paymentsUrl;

    public DeveloperDashboardController(PlatformClient platformClient,
                                        @Value("${services.payments.url:http://localhost:8081}") String paymentsUrl) {
        this.platformClient = platformClient;
        this.paymentsUrl = paymentsUrl;
    }

    @GetMapping("/developer")
    public String dashboard(@RequestParam(name = "merchantId", required = false) String merchantIdParam,
                            Model model) {
        List<Map<String, Object>> merchants = new ArrayList<>();
        Map<String, Object> selectedMerchant = null;
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
        }

        String merchantIdValue = selectedId == null ? "<merchant-id>" : selectedId.toString();
        String keyIdValue = selectedMerchant == null || selectedMerchant.get("keyId") == null
                ? "<key-id>"
                : String.valueOf(selectedMerchant.get("keyId"));

        List<Map<String, Object>> endpoints = buildEndpoints();
        List<Map<String, Object>> testCards = buildTestCards();
        List<Map<String, Object>> sandboxRules = buildSandboxRules();

        model.addAttribute("pageTitle", "Developer");
        model.addAttribute("activeNav", "developer");
        model.addAttribute("merchants", merchants);
        model.addAttribute("selectedMerchant", selectedMerchant);
        model.addAttribute("selectedMerchantId", selectedId == null ? null : selectedId.toString());
        model.addAttribute("keyId", selectedMerchant == null ? null : selectedMerchant.get("keyId"));
        model.addAttribute("baseUrl", paymentsUrl);
        model.addAttribute("endpoints", endpoints);
        model.addAttribute("createPaymentJson", createPaymentJson(merchantIdValue));
        model.addAttribute("payJson", payJson());
        model.addAttribute("createPaymentCurl", createPaymentCurl(merchantIdValue, keyIdValue));
        model.addAttribute("payCurl", payCurl());
        model.addAttribute("captureCurl", captureCurl());
        model.addAttribute("testCards", testCards);
        model.addAttribute("sandboxRules", sandboxRules);
        model.addAttribute("defaultCurrency", DEFAULT_CURRENCY);
        model.addAttribute("defaultDescription", DEFAULT_DESCRIPTION);
        model.addAttribute("defaultCustomerEmail", DEFAULT_CUSTOMER_EMAIL);
        if (errorMessage != null) {
            model.addAttribute("error", errorMessage);
        }
        return "developer/dashboard";
    }

    @PostMapping("/developer/test-payment")
    public String createTestPayment(@RequestParam("merchantId") String merchantIdParam,
                                    @RequestParam(name = "amount", required = false) String amountParam,
                                    @RequestParam(name = "currency", required = false) String currencyParam,
                                    @RequestParam(name = "customerEmail", required = false) String customerEmailParam,
                                    @RequestParam(name = "description", required = false) String descriptionParam,
                                    RedirectAttributes redirectAttributes) {
        UUID merchantId;
        try {
            merchantId = UUID.fromString(merchantIdParam == null ? "" : merchantIdParam.trim());
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", "Select a valid merchant to create a test payment.");
            return "redirect:/developer";
        }

        long amount = parseAmount(amountParam);
        if (amount <= 0L) {
            redirectAttributes.addFlashAttribute("error", "Enter a valid amount in minor units, for example 49900.");
            redirectAttributes.addAttribute("merchantId", merchantId.toString());
            return "redirect:/developer";
        }

        String currency = currencyParam == null || currencyParam.isBlank()
                ? DEFAULT_CURRENCY
                : currencyParam.trim().toUpperCase();
        String customerEmail = customerEmailParam == null || customerEmailParam.isBlank()
                ? DEFAULT_CUSTOMER_EMAIL
                : customerEmailParam.trim();
        String description = descriptionParam == null || descriptionParam.isBlank()
                ? DEFAULT_DESCRIPTION
                : descriptionParam.trim();

        try {
            Map<String, Object> payment = platformClient.createPayment(
                    merchantId, amount, currency, customerEmail, description);
            Object paymentId = payment == null ? null : payment.get("id");
            if (paymentId == null) {
                redirectAttributes.addFlashAttribute("error", "The payment could not be created.");
                redirectAttributes.addAttribute("merchantId", merchantId.toString());
                return "redirect:/developer";
            }
            return "redirect:/checkout/" + paymentId;
        } catch (PlatformClientException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            redirectAttributes.addAttribute("merchantId", merchantId.toString());
            return "redirect:/developer";
        }
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

    private long parseAmount(String amountParam) {
        if (amountParam == null || amountParam.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(amountParam.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private List<Map<String, Object>> buildEndpoints() {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        endpoints.add(endpoint("POST", "/api/v1/payments", "Create a payment in CREATED status for a KYC-verified merchant."));
        endpoints.add(endpoint("POST", "/api/v1/payments/{id}/pay", "Run the risk check and authorize with card details."));
        endpoints.add(endpoint("POST", "/api/v1/payments/{id}/capture", "Capture an authorized payment and post it to the ledger."));
        endpoints.add(endpoint("GET", "/api/v1/payments/{id}", "Fetch a payment by id."));
        endpoints.add(endpoint("GET", "/api/v1/payments?merchantId={merchantId}", "List payments, optionally filtered by merchant."));
        endpoints.add(endpoint("GET", "/api/v1/merchants/{id}", "Fetch a merchant and its KYC status."));
        return endpoints;
    }

    private Map<String, Object> endpoint(String method, String path, String description) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("method", method);
        endpoint.put("path", path);
        endpoint.put("description", description);
        return endpoint;
    }

    private List<Map<String, Object>> buildTestCards() {
        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(testCard("4111111111111111", "Visa", "Approved", "Authorization succeeds and the payment can be captured."));
        cards.add(testCard("4000000000000002", "Visa", "Declined", "Authorization is declined by the sandbox acquirer."));
        return cards;
    }

    private Map<String, Object> testCard(String number, String network, String outcome, String description) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("number", number);
        card.put("network", network);
        card.put("outcome", outcome);
        card.put("description", description);
        return card;
    }

    private List<Map<String, Object>> buildSandboxRules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("Bank payout", "A payout to a bank account number ending in 0000 fails. Any other account returns a UTR-style bank reference."));
        rules.add(rule("KYC verification", "KYC is rejected when the business name contains the word reject. Any other business name is verified."));
        rules.add(rule("Card validation", "Card numbers must pass the Luhn check. The card network is detected from the card number prefix."));
        return rules;
    }

    private Map<String, Object> rule(String title, String description) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("title", title);
        rule.put("description", description);
        return rule;
    }

    private String createPaymentJson(String merchantId) {
        return "{\n"
                + "  \"merchantId\": \"" + merchantId + "\",\n"
                + "  \"amount\": 49900,\n"
                + "  \"currency\": \"INR\",\n"
                + "  \"customerEmail\": \"customer@example.com\",\n"
                + "  \"description\": \"Order #1001\"\n"
                + "}";
    }

    private String payJson() {
        return "{\n"
                + "  \"cardNumber\": \"4111111111111111\",\n"
                + "  \"expiry\": \"12/30\",\n"
                + "  \"cvv\": \"123\",\n"
                + "  \"cardholderName\": \"Test Customer\"\n"
                + "}";
    }

    private String createPaymentCurl(String merchantId, String keyId) {
        return "curl -X POST " + paymentsUrl + "/api/v1/payments \\\n"
                + "  -H \"Content-Type: application/json\" \\\n"
                + "  -H \"X-Key-Id: " + keyId + "\" \\\n"
                + "  -d '{\"merchantId\":\"" + merchantId + "\",\"amount\":49900,\"currency\":\"INR\","
                + "\"customerEmail\":\"customer@example.com\",\"description\":\"Order #1001\"}'";
    }

    private String payCurl() {
        return "curl -X POST " + paymentsUrl + "/api/v1/payments/{paymentId}/pay \\\n"
                + "  -H \"Content-Type: application/json\" \\\n"
                + "  -d '{\"cardNumber\":\"4111111111111111\",\"expiry\":\"12/30\",\"cvv\":\"123\","
                + "\"cardholderName\":\"Test Customer\"}'";
    }

    private String captureCurl() {
        return "curl -X POST " + paymentsUrl + "/api/v1/payments/{paymentId}/capture";
    }
}
