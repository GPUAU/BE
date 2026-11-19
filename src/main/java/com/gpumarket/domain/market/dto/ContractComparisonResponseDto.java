package com.gpumarket.domain.market.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ContractComparisonResponseDto(
    String productId,
    Instant asOf,
    List<ContractOfferDto> items,
    List<DiscountDto> discounts,
    String disclaimer) {

  public record ContractOfferDto(
      String offerId,
      String contractType,
      String contractProductType,
      Integer months,
      String paymentOption,
      String observationId,
      Instant collectedAt,
      String freshness,
      Map<String, Object> effective) {}

  public record DiscountDto(
      String baseOfferId,
      String commitmentOfferId,
      String discountRate,
      String status,
      String reason) {}
}
