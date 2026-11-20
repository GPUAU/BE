package com.gpumarket.domain.simulation.montecarlo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonteCarloPricingTest {
  @Test
  void countsAnExactlyBreakEvenCandidateAsMeetingTheTarget() {
    var config =
        new MonteCarloConfig(
            new MonteCarloConfig.Simulation(1000, 42, 3.0, 0.0, List.of(0.0, 0.0, 0.0)),
            new MonteCarloConfig.Investment(
                1, 12, 12779.0, 6, 0.0, MonteCarloConfig.PricingMode.FIXED),
            new MonteCarloConfig.Uncertain(
                List.of(0.68, 0.68, 0.68),
                List.of(0.0, 0.0, 0.0),
                List.of(900.0, 900.0, 900.0),
                List.of(0.0, 0.0, 0.0)),
            0.0,
            List.of(4.99, 4.999999, 5.0, 5.01));

    var result =
        MonteCarloPricing.calculate(
            config,
            new MonteCarloConfig.Selection(
                MonteCarloConfig.Scenario.BASELINE, null, MonteCarloConfig.Post12Trend.KEEP),
            new double[][] {path(1.0)},
            5.0);

    // At $5, revenue (9.5 × 730 × 0.68 × 5) and cost (12,779 + 12 × 900) both equal $23,579.
    assertThat(result.candidates())
        .extracting(MonteCarloPricing.Candidate::probabilityMeetTarget)
        .containsExactly(0.0, 0.0, 1.0, 1.0);
  }

  @Test
  void matchesAnalyticalFixedPriceAndPreservesUndefinedIrr() {
    var config = config(MonteCarloConfig.PricingMode.FIXED, List.of(0.01, 3.0));
    var result =
        MonteCarloPricing.calculate(
            config,
            new MonteCarloConfig.Selection(
                MonteCarloConfig.Scenario.BASELINE, null, MonteCarloConfig.Post12Trend.KEEP),
            new double[][] {path(1.0)},
            5.0,
            new MonteCarloPricing.ScenarioInputs[] {
              new MonteCarloPricing.ScenarioInputs(0.5, 0.0, 365.0, 0.0)
            });

    assertThat(result.selection().nSelected()).isOne();
    assertThat(result.requiredQuantiles().p50()).isCloseTo(2.0, within(1e-12));
    assertThat(result.candidates().get(0).probabilityMeetTarget()).isZero();
    assertThat(result.candidates().get(0).irrP50()).isNull();
    assertThat(result.candidates().get(0).irrUndefinedCount()).isOne();
    assertThat(result.candidates().get(1).probabilityMeetTarget()).isOne();
    assertThat(result.candidates().get(1).irrP50()).isPositive();
  }

  @Test
  void keepsBandBoundariesAndMarksDirectPriceOutsideCentralRange() {
    var config = config(MonteCarloConfig.PricingMode.FOLLOW_MARKET, List.of(2.0));
    var paths = new double[][] {path(0.5), path(0.5), path(1.0), path(1.0), path(2.0)};
    var inputs = new MonteCarloPricing.ScenarioInputs[paths.length];
    Arrays.fill(inputs, fixedInputs());
    var result =
        MonteCarloPricing.calculate(
            config,
            new MonteCarloConfig.Selection(null, 0.01, MonteCarloConfig.Post12Trend.KEEP),
            paths,
            5.0,
            inputs);

    assertThat(result.selection().nSelected()).isEqualTo(2);
    assertThat(result.selection().warnings()).hasSize(1);
    assertThat(result.requiredQuantiles().p50()).isCloseTo(2.0, within(1e-12));
  }

  private static MonteCarloConfig config(
      MonteCarloConfig.PricingMode mode, List<Double> candidatePrices) {
    return new MonteCarloConfig(
        new MonteCarloConfig.Simulation(1000, 42, 3.0, 0.0, List.of(0.0, 0.0, 0.0)),
        new MonteCarloConfig.Investment(1, 12, 4380.0, 0, 0.0, mode),
        new MonteCarloConfig.Uncertain(
            List.of(0.5, 0.5, 0.5),
            List.of(0.0, 0.0, 0.0),
            List.of(0.0, 0.0, 0.0),
            List.of(0.0, 0.0, 0.0)),
        0.0,
        candidatePrices);
  }

  private static double[] path(double factor) {
    var path = new double[13];
    Arrays.fill(path, factor);
    path[0] = 1.0;
    return path;
  }

  private static MonteCarloPricing.ScenarioInputs fixedInputs() {
    return new MonteCarloPricing.ScenarioInputs(0.5, 0.0, 0.0, 0.0);
  }
}
