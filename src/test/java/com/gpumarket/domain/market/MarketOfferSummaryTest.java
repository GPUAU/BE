package com.gpumarket.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gpumarket.domain.exchange.service.ExchangeService;
import com.gpumarket.domain.market.repository.MarketOfferRepository;
import com.gpumarket.domain.market.service.MarketOfferService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketOfferSummaryTest {
  @Test
  void separatesRegionsAndConfigurationsAndCountsExactPricesRegardlessOfAge() {
    var repository = mock(MarketOfferRepository.class);
    var service = new MarketOfferService(repository, mock(ExchangeService.class));
    var now = Instant.now();
    var old = row(4, "east", 32, "firm", now.minus(49, ChronoUnit.HOURS), "1.0");
    var estimate = row(5, "east", 32, "estimate", now, "0.5");
    var missing = row(6, "east", 32, "firm", now, "0.3");
    missing.put("processing_status", "collection_failed");
    missing.put("checked_at", now);
    missing.put("disclosure_processing_status", "not_found");
    missing.put("disclosure_checked_at", now);
    var unknownConfig = row(7, "east", 32, "firm", now, "0.2");
    unknownConfig.remove("vcpu_count");
    when(repository.offers(anyMap()))
        .thenReturn(
            List.of(
                row(1, "east", 32, "firm", now, "4.0"),
                row(2, "east", 32, "firm", now, "6.0"),
                row(2, "east", 32, "firm", now, "5.0"),
                row(1, "west", 32, "firm", now, "10.0"),
                row(1, "east", 64, "firm", now, "9.0"),
                old,
                estimate,
                missing,
                unknownConfig));

    var result = service.summary(Map.of("modelId", "1"));
    var groups = (List<?>) result.get("groups");
    assertThat(groups).hasSize(3);
    var east = (Map<?, ?>) groups.get(0);
    assertThat(east.get("minimum")).isEqualTo("1.0");
    assertThat(east.get("median")).isEqualTo("4.0");
    assertThat(east.get("maximum")).isEqualTo("5.0");
    assertThat(east.get("sampleCounts"))
        .isEqualTo(
            Map.of("providerCount", 3, "productCount", 3, "offerCount", 4, "observationCount", 4));
    var excluded = (Map<?, ?>) result.get("excludedSummary");
    for (String reason : List.of("nonExactOffers", "notFoundOffers", "incomparableOffers")) {
      assertThat(excluded.get(reason)).isEqualTo(1);
    }
  }

  private static Map<String, Object> row(
      long provider, String region, int cpu, String certainty, Instant observed, String amount) {
    var row = new HashMap<String, Object>();
    row.put("provider_id", provider);
    row.put("product_id", provider);
    row.put("observation_id", 100L);
    row.put("gpu_model_id", 1L);
    row.put("provider_type", "GENERAL_CLOUD");
    row.put("country_code", "US");
    row.put("region_code", region);
    row.put("system_type", "instance");
    row.put("service_option", "standard");
    row.put("gpu_count", 8);
    row.put("vcpu_count", BigDecimal.valueOf(cpu));
    row.put("ram_gb", BigDecimal.valueOf(64));
    row.put("contract_type", "on_demand");
    row.put("contract_product_type", "on_demand");
    row.put("intra_node_interconnect", "NVLink");
    row.put("interconnect_status", "confirmed");
    row.put("price_certainty", certainty);
    row.put("confirmed_at", observed);
    row.put("result_status", "success");
    row.put("effective_usd_gpu_hour", new BigDecimal(amount));
    return row;
  }
}
