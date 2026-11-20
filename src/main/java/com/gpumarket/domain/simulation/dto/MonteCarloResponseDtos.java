package com.gpumarket.domain.simulation.dto;

import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Post12Trend;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket.HistoryEvidence;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket.HistoryPoint;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket.MonthlyRange;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloPricing.PricingResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MonteCarloResponseDtos {
  private MonteCarloResponseDtos() {}

  public record Metadata(
      String modelCode,
      boolean isSample,
      String datasetVersion,
      LocalDate dataAsOf,
      String historyHash,
      String configHash,
      Instant runAt,
      String algorithmVersion,
      String unit,
      int hoursPerMonth,
      int seed,
      int nPaths,
      int analysisMonths,
      List<String> warnings) {}

  public record Forecast(
      Metadata metadata,
      HistoryEvidence evidence,
      List<HistoryPoint> history,
      List<MonthlyRange> monthly) {}

  public record Pricing(
      Metadata metadata,
      Post12Trend post12Trend,
      List<MonthlyRange> monthly,
      PricingResult pricing) {}
}
