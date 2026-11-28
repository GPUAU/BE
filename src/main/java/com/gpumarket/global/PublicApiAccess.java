package com.gpumarket.global;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/** 화면에서 쓰는 공개 API 경로. local·deploy 보안 설정이 같은 목록을 사용한다. */
final class PublicApiAccess {
  private PublicApiAccess() {}

  static void permitPublicApis(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
          auth) {
    auth.requestMatchers(
            HttpMethod.GET,
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
            "/api/v1/interest-rates/latest",
            "/api/v1/operations/alerts",
            "/api/v1/operations/collection-runs")
        .permitAll()
        .requestMatchers(
            HttpMethod.POST,
            "/api/v1/exchange-rate-simulations",
            "/api/v1/agent-messages",
            "/api/v1/cost-simulations",
            "/api/v1/data-center-simulations",
            "/api/v1/irr-simulations",
            "/api/v1/monte-carlo-forecasts",
            "/api/v1/monte-carlo-pricing-simulations")
        .permitAll()
        .requestMatchers(HttpMethod.DELETE, "/api/v1/agent-sessions/*")
        .permitAll();
  }
}
