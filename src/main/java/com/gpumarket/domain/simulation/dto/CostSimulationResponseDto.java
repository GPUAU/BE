package com.gpumarket.domain.simulation.dto;

import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.InterestRateType;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ItemType;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.PricingMethod;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CostSimulationResponseDto(
    String unit,
    String hourlyCost,
    String candidatePrice,
    String estimatedMarginRate,
    PricingMethod pricingMethod,
    String annualCost,
    String billableGpuHoursPerYear,
    String periodCost,
    int periodHours,
    String billableGpuHoursForPeriod,
    String periodElectricityCost,
    String formulaVersion,
    String formulaSummary,
    AppliedAssumptions appliedAssumptions,
    String costCompleteness,
    List<ItemType> missingItems,
    List<String> warnings,
    MarketComparison marketComparison,
    boolean saved,
    String disclaimer) {
  public record AppliedAssumptions(
      int gpuCount,
      int depreciationYears,
      String utilization,
      String annualInterestRateUsed,
      InterestRateType interestRateType,
      boolean interestWasProvided,
      String inputFxKrwPerUsd,
      String capitalRecoveryFactor,
      String acquisitionCostUsd,
      String annualOperatingCostUsd,
      String annualOperatingCostExcludingMeteredElectricityUsd,
      String annualCapitalRecoveryUsd,
      String residualValueUsd,
      String calendarMonth,
      String annualStraightLineDepreciationUsd,
      List<CapitalAsset> capitalAssets,
      boolean assetSpecificLives) {}

  public record CapitalAsset(
      ItemType itemType,
      int usefulLifeYears,
      String acquisitionCostUsd,
      String residualValueUsd,
      String capitalRecoveryFactor,
      String annualCapitalRecoveryUsd,
      String annualStraightLineDepreciationUsd) {}

  public record MarketComparison(String status, String reason, List<MarketGroup> groups) {}

  public record MarketGroup(
      Map<String, Object> groupKey,
      Map<String, Integer> sampleCounts,
      List<MarketOfferResult> offerResults,
      List<Map<String, Object>> sources,
      Instant asOf) {}

  public record MarketOfferResult(
      String offerId,
      Map<String, Object> price,
      String estimatedMargin,
      String status,
      String reason,
      Instant collectedAt,
      List<String> evidenceIds) {}
}
