package com.gpumarket.domain.market.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MarketAvailabilityResponseDto(
    List<AvailabilityDto> items, Instant asOf, String nextCursor) {

  public record AvailabilityDto(
      String targetId,
      String providerId,
      String modelId,
      String productId,
      String offerId,
      DisclosureDto latestDisclosure,
      ProcessingCheckDto latestCheck,
      String scopeDescription,
      List<Map<String, Object>> evidence,
      Instant firstPriceObservedAt,
      Map<String, Object> lastSuccessfulObservation,
      FreshnessDto freshness) {}

  public record DisclosureDto(
      String status, Instant verifiedAt, List<Map<String, Object>> evidence) {}

  public record ProcessingCheckDto(String processingStatus, Instant checkedAt, String reason) {}

  public record FreshnessDto(
      String status, Instant lastSuccessAt, Instant evaluatedAt, String ruleVersion) {}
}
