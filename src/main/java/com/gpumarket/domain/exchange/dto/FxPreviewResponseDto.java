package com.gpumarket.domain.exchange.dto;

import java.util.List;

public record FxPreviewResponseDto(
    String rateType,
    String priceType,
    String baseCurrency,
    String quoteCurrency,
    String krwPerUsd,
    List<Item> items,
    boolean saved,
    String warning) {
  public record Item(
      String observationId,
      String normalizationId,
      String ruleVersion,
      String usdPerGpuHour,
      String krwPerGpuHour,
      String unit,
      String status,
      String reason) {}
}
