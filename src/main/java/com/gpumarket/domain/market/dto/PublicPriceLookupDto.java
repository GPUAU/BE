package com.gpumarket.domain.market.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PublicPriceLookupDto(List<PublicPriceDto> items, Instant asOf, boolean limited) {
  public record PublicPriceDto(
      String offerId,
      String observationId,
      String normalizationId,
      String productId,
      String providerId,
      String providerName,
      String rawPriceText,
      List<Map<String, Object>> rawComponents,
      Integer gpuCount,
      String countryCode,
      String regionCode,
      String systemType,
      String contractType,
      Instant collectedAt,
      String priceUsdGpuHour,
      List<Map<String, Object>> sources) {}
}
