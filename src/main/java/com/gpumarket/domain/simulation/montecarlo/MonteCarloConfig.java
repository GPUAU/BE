package com.gpumarket.domain.simulation.montecarlo;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ExactInteger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MonteCarloConfig(
    @Valid @NotNull Simulation simulation,
    @Valid @NotNull Investment investment,
    @Valid @NotNull Uncertain uncertain,
    @NotNull Double targetIrrAnnual,
    @NotNull @Size(min = 1, max = 8) List<@NotNull Double> candidatePrices) {

  public record Simulation(
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(1000) @Max(50000) Integer nPaths,
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(0) Integer seed,
      @NotNull Double meanBlockLength,
      @NotNull Double jumpProbPerMonth,
      @NotNull @Size(min = 3, max = 3) List<@NotNull Double> jumpSizeTri) {}

  public record Investment(
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(1) @Max(100000) Integer gpus,
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(12) @Max(60) Integer months,
      @NotNull Double capexPerGpu,
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(0) @Max(60) Integer rampMonths,
      @NotNull Double residualValuePct,
      @NotNull PricingMode pricingMode) {}

  public record Uncertain(
      @NotNull @Size(min = 3, max = 3) List<@NotNull Double> utilization,
      @NotNull @Size(min = 3, max = 3) List<@NotNull Double> capexOverrun,
      @NotNull @Size(min = 3, max = 3) List<@NotNull Double> opexPerGpuMonth,
      @NotNull @Size(min = 3, max = 3) List<@NotNull Double> opexAnnualInflation) {}

  public record Selection(
      Scenario scenario, Double marketPrice12m, @NotNull Post12Trend post12Trend) {}

  public enum PricingMode {
    FIXED,
    FOLLOW_MARKET
  }

  public enum Scenario {
    CONSERVATIVE,
    BASELINE,
    OPTIMISTIC
  }

  public enum Post12Trend {
    KEEP,
    EASE,
    FLAT
  }
}
