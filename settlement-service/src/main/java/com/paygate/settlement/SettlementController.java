package com.paygate.settlement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.OK)
    public List<SettlementResponse> run(@RequestBody(required = false) RunSettlementRequest request,
                                        @RequestParam(name = "merchantId", required = false) UUID merchantIdParam) {
        UUID merchantId = request != null && request.merchantId() != null ? request.merchantId() : merchantIdParam;
        return settlementService.run(merchantId).stream().map(SettlementResponse::from).toList();
    }

    @GetMapping
    public List<SettlementResponse> list(@RequestParam(name = "merchantId", required = false) UUID merchantId) {
        return settlementService.list(merchantId).stream().map(SettlementResponse::from).toList();
    }

    public record RunSettlementRequest(UUID merchantId) {
    }

    public record SettlementResponse(
            UUID id,
            UUID merchantId,
            long amount,
            String currency,
            Settlement.SettlementStatus status,
            String bankReference,
            String failureReason,
            Instant createdAt) {

        static SettlementResponse from(Settlement settlement) {
            return new SettlementResponse(
                    settlement.getId(),
                    settlement.getMerchantId(),
                    settlement.getAmount(),
                    settlement.getCurrency(),
                    settlement.getStatus(),
                    settlement.getBankReference(),
                    settlement.getFailureReason(),
                    settlement.getCreatedAt());
        }
    }
}
