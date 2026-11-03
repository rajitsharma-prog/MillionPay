package com.paygate.payments.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ServiceClientConfig {

    @Bean
    public RestClient riskClient(@Value("${services.risk.url:http://localhost:8084}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient ledgerClient(@Value("${services.ledger.url:http://localhost:8082}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient partnerClient(@Value("${services.partner.url:http://localhost:8085}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
