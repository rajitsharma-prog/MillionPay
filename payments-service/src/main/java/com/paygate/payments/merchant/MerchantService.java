package com.paygate.payments.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MerchantService {

    private static final String KEY_PREFIX = "pg_key_";

    private final MerchantRepository merchantRepository;
    private final RestClient partnerClient;

    public MerchantService(MerchantRepository merchantRepository, RestClient partnerClient) {
        this.merchantRepository = merchantRepository;
        this.partnerClient = partnerClient;
    }

    @Transactional
    public Merchant onboard(String name, String email, String businessType) {
        Merchant merchant = new Merchant(name, email, businessType, generateKeyId());
        merchant.setId(UUID.randomUUID());
        merchant = merchantRepository.save(merchant);
        return runKyc(merchant);
    }

    @Transactional
    public Merchant rerunKyc(UUID id) {
        Merchant merchant = get(id);
        return runKyc(merchant);
    }

    @Transactional(readOnly = true)
    public List<Merchant> list() {
        return merchantRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Merchant get(UUID id) {
        return merchantRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant not found: " + id));
    }

    private Merchant runKyc(Merchant merchant) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("merchantId", merchant.getId().toString());
        request.put("businessName", merchant.getName());
        request.put("email", merchant.getEmail());
        request.put("businessType", merchant.getBusinessType());

        Map<String, Object> response;
        try {
            response = partnerClient.post()
                    .uri("/sandbox/kyc/verify")
                    .body(request)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "KYC partner is unavailable", ex);
        }

        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "KYC partner returned an empty response");
        }

        merchant.setKycStatus(resolveStatus(response));
        Object reference = response.get("reference");
        if (reference == null) {
            reference = response.get("kycReference");
        }
        merchant.setKycReference(reference == null ? null : reference.toString());
        return merchantRepository.save(merchant);
    }

    private Merchant.KycStatus resolveStatus(Map<String, Object> response) {
        Object verified = response.get("verified");
        if (verified instanceof Boolean flag) {
            return flag ? Merchant.KycStatus.VERIFIED : Merchant.KycStatus.REJECTED;
        }
        Object status = response.get("status");
        if (status != null) {
            String value = status.toString().trim().toUpperCase();
            if ("VERIFIED".equals(value) || "APPROVED".equals(value)) {
                return Merchant.KycStatus.VERIFIED;
            }
            if ("REJECTED".equals(value) || "FAILED".equals(value)) {
                return Merchant.KycStatus.REJECTED;
            }
        }
        return Merchant.KycStatus.PENDING;
    }

    private String generateKeyId() {
        String keyId;
        do {
            keyId = KEY_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        } while (merchantRepository.findByKeyId(keyId).isPresent());
        return keyId;
    }
}
