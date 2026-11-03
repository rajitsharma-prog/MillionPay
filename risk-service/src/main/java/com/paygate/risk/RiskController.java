package com.paygate.risk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @PostMapping("/evaluate")
    public EvaluateResponse evaluate(@Valid @RequestBody EvaluateRequest request) {
        RiskService.Evaluation evaluation = riskService.evaluate(
                request.amount(),
                request.currency(),
                request.cardCurrency(),
                request.cardFingerprint(),
                request.customerEmail());
        return new EvaluateResponse(
                evaluation.score(),
                evaluation.decision(),
                evaluation.triggeredRules());
    }

    public record EvaluateRequest(
            String paymentId,
            String merchantId,
            @NotNull @PositiveOrZero Long amount,
            String currency,
            String cardCurrency,
            String cardFingerprint,
            String customerEmail) {
    }

    public record EvaluateResponse(
            int score,
            RiskService.Decision decision,
            List<String> triggeredRules) {
    }
}
