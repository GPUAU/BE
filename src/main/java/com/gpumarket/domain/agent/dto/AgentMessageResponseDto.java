package com.gpumarket.domain.agent.dto;

import com.gpumarket.domain.market.dto.ContractComparisonResponseDto;
import com.gpumarket.domain.market.dto.MarketAvailabilityResponseDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentMessageResponseDto(
    String sessionId,
    String status,
    String answer,
    PublicContextDto appliedConditions,
    List<String> assumptions,
    List<String> retainedConditions,
    List<String> changedConditions,
    List<String> clarificationFields,
    List<AgentResultDto> results,
    Map<String, Object> history,
    ContractComparisonResponseDto contracts,
    MarketAvailabilityResponseDto availability,
    List<Map<String, Object>> sources,
    SampleCountsDto sampleCounts,
    String sampleStatus,
    String aggregationUnit,
    Instant asOf,
    String disclaimer) {

  public AgentMessageResponseDto withSession(
      String id, List<String> retained, List<String> changed) {
    return new AgentMessageResponseDto(
        id,
        status,
        answer,
        appliedConditions,
        assumptions,
        retained,
        changed,
        clarificationFields,
        results,
        history,
        contracts,
        availability,
        sources,
        sampleCounts,
        sampleStatus,
        aggregationUnit,
        asOf,
        disclaimer);
  }

  public record AgentResultDto(
      String offerId,
      String observationId,
      String normalizationId,
      String rawPriceText,
      List<Map<String, Object>> rawComponents,
      String providerName,
      Integer gpuCount,
      String countryCode,
      String regionCode,
      String systemType,
      String contractType,
      Instant collectedAt,
      String priceUsdGpuHour,
      String priceType,
      String status,
      List<String> evidenceIds) {}

  public record SampleCountsDto(
      long providerCount, long productCount, long offerCount, long observationCount) {}
}
