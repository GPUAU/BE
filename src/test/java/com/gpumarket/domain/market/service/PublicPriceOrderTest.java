package com.gpumarket.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpumarket.domain.market.dto.PublicPriceLookupDto.PublicPriceDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicPriceOrderTest {
  private static PublicPriceDto price(String offerId, String provider, String usd) {
    return new PublicPriceDto(
        offerId,
        null,
        null,
        null,
        null,
        provider,
        null,
        List.of(),
        8,
        "US",
        "us-east-1",
        null,
        "ON_DEMAND",
        null,
        usd,
        List.of());
  }

  @Test
  void picksCheapestOffersAcrossProvidersBeforeOfferOrder() {
    var items =
        List.of(
            price("1", "AWS", "21.3625000000"),
            price("2", "AWS", "17.8020000000"),
            price("3", "AWS", "25.1008200000"),
            price("900", "Hyperstack", "7.4000000000"),
            price("901", "Verda", "13.5800000000"),
            price("902", "Nebius", null));

    var picked = MarketOfferService.cheapestFirst(items, 3);

    assertThat(picked).extracting(PublicPriceDto::offerId).containsExactly("900", "901", "2");
  }

  @Test
  void keepsOffersWithoutPriceLastAndToleratesBadNumbers() {
    var items = List.of(price("1", "A", null), price("2", "B", "abc"), price("3", "C", "1.5"));

    assertThat(MarketOfferService.cheapestFirst(items, 5))
        .extracting(PublicPriceDto::offerId)
        .startsWith("3");
  }
}
