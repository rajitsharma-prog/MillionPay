package com.paygate.payments.merchant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse create(@Valid @RequestBody CreateMerchantRequest request) {
        Merchant merchant = merchantService.onboard(request.name(), request.email(), request.businessType());
        return MerchantResponse.from(merchant);
    }

    @GetMapping
    public List<MerchantResponse> list() {
        return merchantService.list().stream().map(MerchantResponse::from).toList();
    }

    @GetMapping("/{id}")
    public MerchantResponse get(@PathVariable("id") UUID id) {
        return MerchantResponse.from(merchantService.get(id));
    }

    @PostMapping("/{id}/kyc")
    public MerchantResponse rerunKyc(@PathVariable("id") UUID id) {
        return MerchantResponse.from(merchantService.rerunKyc(id));
    }

    public record CreateMerchantRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            String businessType) {
    }

    public record MerchantResponse(
            UUID id,
            String name,
            String email,
            String businessType,
            Merchant.KycStatus kycStatus,
            String kycReference,
            String keyId,
            Instant createdAt) {

        static MerchantResponse from(Merchant merchant) {
            return new MerchantResponse(
                    merchant.getId(),
                    merchant.getName(),
                    merchant.getEmail(),
                    merchant.getBusinessType(),
                    merchant.getKycStatus(),
                    merchant.getKycReference(),
                    merchant.getKeyId(),
                    merchant.getCreatedAt());
        }
    }
}
