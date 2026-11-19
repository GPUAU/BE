package com.gpumarket.domain.market.dto;

import com.gpumarket.domain.exchange.dto.LatestExchangeRateResponseDto.Source;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record MarketChangesResponseDto(
    List<PriceChangeDto> items, Instant asOf, String nextCursor) {
  public record PriceChangeDto(
      String eventId,
      Instant eventAt,
      String eventType,
      String offerId,
      String previousOfferId,
      String currentOfferId,
      String previousObservationId,
      String currentObservationId,
      String previousNormalizationId,
      String currentNormalizationId,
      String previousRuleVersion,
      String currentRuleVersion,
      String previousPrice,
      String currentPrice,
      String difference,
      String changeRate,
      Instant firstObservedAt,
      Instant previousObservedAt,
      Instant currentObservedAt,
      List<String> previousEvidenceIds,
      List<String> currentEvidenceIds,
      List<String> previousSourceIds,
      List<String> currentSourceIds,
      String lastUndisclosedCheckId,
      Instant lastUndisclosedAt,
      List<String> lastUndisclosedEvidenceIds,
      List<Map<String, Object>> lastUndisclosedEvidence,
      PriceUnitDto previousUnit,
      PriceUnitDto currentUnit,
      String reason,
      KrwDecompositionDto krwDecomposition) {}

  public record PriceUnitDto(String currency, String timeUnit, String appliesTo) {}

  public record KrwDecompositionDto(
      String status,
      String reason,
      String previousObservationId,
      String currentObservationId,
      String previousExchangeRateId,
      String currentExchangeRateId,
      String previousUsd,
      String currentUsd,
      String previousRate,
      String currentRate,
      LocalDate previousReferenceDate,
      LocalDate currentReferenceDate,
      Source previousSource,
      Source currentSource,
      String previousSelectionPolicyVersion,
      String currentSelectionPolicyVersion,
      String priceEffectKrw,
      String exchangeEffectKrw) {}
}
