package com.gpumarket.domain.simulation.montecarlo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ExactInteger;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloJson.ExactNumber;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloJson.StrictFields;
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
    @JsonDeserialize(using = ExactNumber.class) @NotNull Double targetIrrAnnual,
    @JsonDeserialize(contentUsing = ExactNumber.class) @NotNull @Size(min = 1, max = 8)
        List<@NotNull Double> candidatePrices)
    implements StrictFields {

  public record Simulation(
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(1000) @Max(50000) Integer nPaths,
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(0) Integer seed,
      @JsonDeserialize(using = ExactNumber.class) @NotNull Double meanBlockLength,
      @JsonDeserialize(using = ExactNumber.class) @NotNull Double jumpProbPerMonth,
      @JsonDeserialize(contentUsing = ExactNumber.class) @NotNull @Size(min = 3, max = 3)
          List<@NotNull Double> jumpSizeTri)
      implements StrictFields {}

  public record Investment(
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(1) @Max(100000) Integer gpus,
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(12) @Max(60) Integer months,
      @JsonDeserialize(using = ExactNumber.class) @NotNull Double capexPerGpu,
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(0) @Max(60) Integer rampMonths,
      @JsonDeserialize(using = ExactNumber.class) @NotNull Double residualValuePct,
      @NotNull PricingMode pricingMode)
      implements StrictFields {}

  public record Uncertain(
      @JsonDeserialize(contentUsing = ExactNumber.class) @NotNull @Size(min = 3, max = 3)
          List<@NotNull Double> utilization,
      @JsonDeserialize(contentUsing = ExactNumber.class) @NotNull @Size(min = 3, max = 3)
          List<@NotNull Double> capexOverrun,
      @JsonDeserialize(contentUsing = ExactNumber.class) @NotNull @Size(min = 3, max = 3)
          List<@NotNull Double> opexPerGpuMonth,
      @JsonDeserialize(contentUsing = ExactNumber.class) @NotNull @Size(min = 3, max = 3)
          List<@NotNull Double> opexAnnualInflation)
      implements StrictFields {}

  public record Selection(
      Scenario scenario,
      @JsonDeserialize(using = ExactNumber.class) Double marketPrice12m,
      @NotNull Post12Trend post12Trend)
      implements StrictFields {}

  public enum PricingMode {
    FIXED,
    FOLLOW_MARKET;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PricingMode parse(JsonNode value) {
      return MonteCarloJson.enumName(value, PricingMode.class);
    }
  }

  public enum Scenario {
    CONSERVATIVE,
    BASELINE,
    OPTIMISTIC;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Scenario parse(JsonNode value) {
      return MonteCarloJson.enumName(value, Scenario.class);
    }
  }

  public enum Post12Trend {
    KEEP,
    EASE,
    FLAT;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Post12Trend parse(JsonNode value) {
      return MonteCarloJson.enumName(value, Post12Trend.class);
    }
  }
}
