package com.gpumarket.domain.exchange.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LatestExchangeRateResponseDto(
    String status,
    String exchangeRateId,
    String baseCurrency,
    String quoteCurrency,
    String rate,
    LocalDate referenceDate,
    Instant collectedAt,
    Source source,
    String selectionPolicyVersion,
    Boolean isPreviousBusinessDay,
    String reason,
    List<String> warnings) {
  public record Source(String sourceId, String name, String url) {}
}
