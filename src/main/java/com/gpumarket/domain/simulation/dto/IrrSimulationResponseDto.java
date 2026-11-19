package com.gpumarket.domain.simulation.dto;

import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.InterestRateType;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ItemType;
import com.gpumarket.domain.simulation.dto.IrrSimulationRequestDto.MarketConstraint;
import java.util.List;
import java.util.Map;

public record IrrSimulationResponseDto(
    String resultStatus,
    String reason,
    AppliedAssumptions assumptions,
    List<Candidate> candidateResults,
    MarketReference marketReference,
    TargetPrice targetPrice,
    List<MarketScenario> marketScenarios,
    String formulaVersion,
    String costCompleteness,
    List<ItemType> missingItems,
    List<String> warnings,
    boolean saved,
    String disclaimer) {

  public record AppliedAssumptions(
      String investment,
      String investmentBasis,
      int analysisYears,
      String residualValue,
      String targetIrr,
      String utilization,
      int gpuCount,
      String annualOperatingCost,
      String acquisitionCostUsd,
      Boolean investmentDiffersFromAcquisition,
      InterestRateType interestRateType,
      String annualInterestRate,
      boolean financingIncludedInCashFlow) {}

  public record CashFlow(int year, String amount) {}

  public record Candidate(
      String priceUsdGpuHour, String status, String reason, List<CashFlow> cashFlows, String irr) {}

  public record MarketReference(
      MarketConstraint type,
      String status,
      String amount,
      String reason,
      Map<String, Integer> sampleCounts,
      List<Map<String, Object>> sources) {}

  public record TargetPrice(String amount, String status, String reason, String irr) {}

  public record MarketScenario(
      MarketConstraint type,
      String price,
      String irr,
      String status,
      String reason,
      Map<String, Integer> sampleCounts,
      List<Map<String, Object>> sources) {}
}
