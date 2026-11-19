package com.gpumarket.global;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("local & !deploy & !prod & !production")
public class LocalSecurityConfig {
  @Bean
  SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.GET,
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/api/v1/catalog",
                        "/api/v1/market/observations/*",
                        "/api/v1/market/price-histories",
                        "/api/v1/market/changes",
                        "/api/v1/market/availability-records",
                        "/api/v1/market/offers",
                        "/api/v1/market/offers/*",
                        "/api/v1/market/summaries",
                        "/api/v1/market/products/*/contracts",
                        "/api/v1/exchange-rates/latest",
                        "/api/v1/operations/alerts",
                        "/api/v1/operations/collection-runs")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/exchange-rate-simulations",
                        "/api/v1/agent-messages")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
