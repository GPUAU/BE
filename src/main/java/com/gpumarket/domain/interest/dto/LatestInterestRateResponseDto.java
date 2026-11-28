package com.gpumarket.domain.interest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 최신 공시 금리. annualRate는 소수(0.04416 = 연 4.416%), annualRatePercent는 공시 원문 그대로.
 *
 * <p>키가 없거나 가져오지 못하면 status=NOT_AVAILABLE과 이유(warnings)만 내려간다.
 */
public record LatestInterestRateResponseDto(
    String status,
    String seriesName,
    String annualRatePercent,
    BigDecimal annualRate,
    LocalDate referenceDate,
    Instant fetchedAt,
    Source source,
    List<String> warnings) {
  public record Source(String name, String statCode, String itemCode, String url) {}
}
