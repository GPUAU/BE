package com.gpumarket.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.web.server.ResponseStatusException;

class ContractComparisonTest {
  private final MarketOfferRepository repository = mock(MarketOfferRepository.class);
  private final MarketOfferService service =
      new MarketOfferService(repository, mock(ExchangeService.class));

  @Test
  void calculatesFirmDiscountAndKeepsSpotAndEstimateOutOfFirmDiscounts() {
    var estimate = offer(4, "commitment", "0");
    estimate.put("price_certainty", "estimate");
    estimate.put("result_status", "assumption");
    when(repository.components(List.of(104L)))
        .thenReturn(
            List.of(
                Map.of(
                    "observation_id",
                    104L,
                    "component_type",
                    "discount_rate",
                    "discount_rate",
                    new BigDecimal("0.15"))));
    when(repository.offers(anyMap()))
        .thenReturn(
            List.of(
                offer(1, "on_demand", "10"),
                offer(2, "commitment", "8"),
                offer(3, "spot", "5"),
                estimate));

    var result = service.contracts(20, Map.of());

    assertThat(result.items()).hasSize(4);
    assertThat(result.discounts()).hasSize(2);
    assertThat(result.discounts().get(0).discountRate()).isEqualTo("0.200000");
    assertThat(result.discounts().get(1).reason()).isEqualTo("COMMITMENT_PRICE_NOT_EXACT");
    assertThat(result.items().get(3).effective())
        .containsEntry("kind", "SCENARIO")
        .containsEntry("amount", "8.500000")
        .containsEntry("baselineObservationId", "101");
  }

  @Test
  void acceptsOlderPricesAndRejectsIncompatibleOrAmbiguousBaselines() {
    var stale = offer(3, "commitment", "6");
    stale.put("confirmed_at", Instant.now().minus(49, ChronoUnit.HOURS));
    var incompatible = offer(4, "commitment", "5");
    incompatible.put("rule_version", "v2");
    when(repository.offers(anyMap()))
        .thenReturn(List.of(offer(1, "on_demand", "10"), stale, incompatible))
        .thenReturn(List.of(offer(1, "on_demand", "10"), offer(2, "on_demand", "9"), incompatible));

    var first = service.contracts(20, Map.of());
    assertThat(first.items().get(1).freshness()).isEqualTo("CURRENT");
    assertThat(first.discounts().get(0).reason()).isNull();
    assertThat(first.discounts().get(1).reason()).isEqualTo("CONDITIONS_NOT_COMPARABLE");
    assertThat(service.contracts(20, Map.of()).discounts().get(0).reason())
        .isEqualTo("AMBIGUOUS_BASE_PRICE");
  }

  @Test
  void doesNotPriceAnUnavailableOfferOrAnEstimateWithUnknownContractTerms() {
    var unavailable = offer(2, "commitment", "8");
    unavailable.put("disclosure_processing_status", "not_found");
    unavailable.put("disclosure_checked_at", Instant.now());
    var estimate = offer(3, "commitment", "0");
    estimate.put("price_certainty", "estimate");
    estimate.put("result_status", "assumption");
    estimate.remove("payment_option");
    when(repository.components(List.of(103L)))
        .thenReturn(
            List.of(
                Map.of(
                    "observation_id",
                    103L,
                    "component_type",
                    "discount_rate",
                    "discount_rate",
                    new BigDecimal("0.15"))));
    when(repository.offers(anyMap()))
        .thenReturn(List.of(offer(1, "on_demand", "10"), unavailable, estimate));

    var result = service.contracts(20, Map.of());
    assertThat(result.items().get(1).effective())
        .containsEntry("status", "UNAVAILABLE")
        .containsEntry("amount", null);
    assertThat(result.items().get(1).observationId()).isEqualTo("102");
    assertThat(result.items().get(2).effective()).containsEntry("amount", null);
  }

  @Test
  void returnsAnEmptyResultOnlyForAnExistingProduct() {
    when(repository.productExists(20)).thenReturn(true);

    assertThat(service.contracts(20, Map.of()).items()).isEmpty();
    assertThatThrownBy(() -> service.contracts(21, Map.of()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  private static Map<String, Object> offer(long id, String type, String amount) {
    var row = new HashMap<String, Object>();
    row.put("offer_id", id);
    row.put("contract_type", type);
    if ("commitment".equals(type)) {
      row.put("commitment_months", 12);
      row.put("payment_option", "no_upfront");
    }
    row.put("observation_id", 100L + id);
    row.put("normalization_id", 200L + id);
    row.put("confirmed_at", Instant.now());
    row.put("price_certainty", "firm");
    row.put("result_status", "success");
    row.put("rule_version", "v1");
    row.put("effective_usd_gpu_hour", new BigDecimal(amount));
    return row;
  }
}
