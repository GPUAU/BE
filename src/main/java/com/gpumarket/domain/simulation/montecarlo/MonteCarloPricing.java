package com.gpumarket.domain.simulation.montecarlo;

import static com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket.ANCHOR_MONTH;
import static com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket.quantile;

import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.PricingMode;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Scenario;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Selection;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Uncertain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

public final class MonteCarloPricing {
  private static final double SELECTION_BAND = 0.10;
  private static final int IRR_SAMPLE_LIMIT = 1000;
  private static final long INPUT_STREAM_SALT = 0x6CB46B89A75ED6CCL;

  private MonteCarloPricing() {}

  public static PricingResult calculate(
      MonteCarloConfig config, Selection selection, double[][] paths, double lastPrice) {
    return calculate(config, selection, paths, lastPrice, drawInputs(config, paths.length));
  }

  public static PricingResult calculate(
      MonteCarloConfig config,
      Selection selection,
      double[][] paths,
      double lastPrice,
      ScenarioInputs[] sampleInputs) {
    if (sampleInputs.length != paths.length || paths.length == 0) {
      throw new IllegalArgumentException("가격 경로와 운영 가정 표본 수가 일치해야 합니다.");
    }

    double[] anchorPrices = MonteCarloMarket.monthPrices(paths, lastPrice, ANCHOR_MONTH);
    double[] sortedAnchorPrices = anchorPrices.clone();
    Arrays.sort(sortedAnchorPrices);
    SelectedPaths selected =
        select(anchorPrices, sortedAnchorPrices, selection.scenario(), selection.marketPrice12m());

    int[] indices = selected.indices();
    if (indices.length == 0) {
      throw new IllegalArgumentException("선택한 시장가격 구간에 계산할 경로가 없습니다.");
    }

    double[] requiredByPath = new double[paths.length];
    for (int index = 0; index < paths.length; index++) {
      requiredByPath[index] =
          MonteCarloCashFlow.project(config.investment(), sampleInputs[index], paths[index])
              .requiredPrice(config.targetIrrAnnual());
    }

    double[] required = sortedRequired(requiredByPath, indices);
    var quantiles =
        new RequiredQuantiles(
            quantile(required, 0.50),
            quantile(required, 0.70),
            quantile(required, 0.80),
            quantile(required, 0.90));

    List<Candidate> candidates = candidates(config, paths, sampleInputs, indices, required);
    double[] medianPath = quantilePath(paths, indices, 0.50);
    ScenarioInputs base = baseInputs(config.uncertain());
    double baseRequired =
        MonteCarloCashFlow.project(config.investment(), base, medianPath)
            .requiredPrice(config.targetIrrAnnual());
    return new PricingResult(
        selected.summary(),
        quantiles,
        candidates,
        baseRequired,
        sensitivity(config, paths, indices, medianPath, base),
        compareScenarios(requiredByPath, anchorPrices, sortedAnchorPrices));
  }

  private static SelectedPaths select(
      double[] anchorPrices, double[] sortedAnchorPrices, Scenario scenario, Double marketPrice) {
    if ((scenario == null) == (marketPrice == null)) {
      throw new IllegalArgumentException("시장가 시나리오 또는 직접 입력 가격 중 하나를 선택하세요.");
    }

    List<String> warnings = List.of();
    double center;
    if (scenario != null) {
      center =
          switch (scenario) {
            case CONSERVATIVE -> 0.25;
            case BASELINE -> 0.50;
            case OPTIMISTIC -> 0.75;
          };
    } else {
      center = MonteCarloMarket.probabilityAtOrBelow(sortedAnchorPrices, marketPrice);
      if (center < 0.05 || center > 0.95) {
        warnings = List.of("직접 입력 시장가격이 12개월 가격 분포의 양 끝 5% 밖에 있습니다.");
      }
      center = Math.min(Math.max(center, SELECTION_BAND), 1.0 - SELECTION_BAND);
    }

    double lowerQuantile = Math.max(0.0, center - SELECTION_BAND);
    double upperQuantile = Math.min(1.0, center + SELECTION_BAND);
    double lowerPrice = quantile(sortedAnchorPrices, lowerQuantile);
    double upperPrice = quantile(sortedAnchorPrices, upperQuantile);

    int[] indices = new int[anchorPrices.length];
    int count = 0;
    for (int index = 0; index < anchorPrices.length; index++) {
      if (lowerPrice <= anchorPrices[index] && anchorPrices[index] <= upperPrice) {
        indices[count++] = index;
      }
    }

    return new SelectedPaths(
        Arrays.copyOf(indices, count),
        new SelectionResult(
            scenario,
            marketPrice,
            center,
            lowerQuantile,
            upperQuantile,
            lowerPrice,
            upperPrice,
            count,
            warnings));
  }

  private static double[] sortedRequired(double[] requiredByPath, int[] indices) {
    double[] values = new double[indices.length];
    for (int index = 0; index < indices.length; index++) {
      values[index] = requiredByPath[indices[index]];
    }
    Arrays.sort(values);
    return values;
  }

  private static List<Candidate> candidates(
      MonteCarloConfig config,
      double[][] paths,
      ScenarioInputs[] sampleInputs,
      int[] indices,
      double[] required) {
    int sampleSize = Math.min(indices.length, IRR_SAMPLE_LIMIT);
    var sampledCashFlows = new MonteCarloCashFlow[sampleSize];
    for (int i = 0; i < sampleSize; i++) {
      int index = indices[i];
      sampledCashFlows[i] =
          MonteCarloCashFlow.project(config.investment(), sampleInputs[index], paths[index]);
    }

    List<Candidate> result = new ArrayList<>();
    for (double price : config.candidatePrices().stream().distinct().sorted().toList()) {
      double[] irrs = new double[sampleSize];
      int defined = 0;
      for (var cashFlow : sampledCashFlows) {
        Double irr = cashFlow.annualIrrAt(price);
        if (irr != null) {
          irrs[defined++] = irr;
        }
      }

      double[] sortedIrrs = Arrays.copyOf(irrs, defined);
      Arrays.sort(sortedIrrs);
      result.add(
          new Candidate(
              price,
              MonteCarloMarket.probabilityAtOrBelow(required, price),
              defined == 0 ? null : quantile(sortedIrrs, 0.10),
              defined == 0 ? null : quantile(sortedIrrs, 0.50),
              defined == 0 ? null : quantile(sortedIrrs, 0.90),
              sampleSize,
              sampleSize - defined));
    }
    return List.copyOf(result);
  }

  private static List<ScenarioComparison> compareScenarios(
      double[] requiredByPath, double[] anchorPrices, double[] sortedAnchorPrices) {
    List<ScenarioComparison> result = new ArrayList<>();
    for (Scenario scenario : Scenario.values()) {
      int[] indices = select(anchorPrices, sortedAnchorPrices, scenario, null).indices();
      if (indices.length == 0) {
        result.add(new ScenarioComparison(scenario, null, null, null, 0));
        continue;
      }

      double[] required = sortedRequired(requiredByPath, indices);
      double[] prices = new double[indices.length];
      for (int i = 0; i < indices.length; i++) {
        prices[i] = anchorPrices[indices[i]];
      }
      Arrays.sort(prices);
      result.add(
          new ScenarioComparison(
              scenario,
              quantile(prices, 0.50),
              quantile(required, 0.50),
              quantile(required, 0.80),
              indices.length));
    }
    return List.copyOf(result);
  }

  private static List<Sensitivity> sensitivity(
      MonteCarloConfig config,
      double[][] paths,
      int[] indices,
      double[] medianPath,
      ScenarioInputs base) {
    List<Sensitivity> result = new ArrayList<>();
    for (Factor factor : Factor.values()) {
      List<Double> inputRange = factor.range(config.uncertain());
      double low = inputRange.get(0);
      double high = inputRange.get(2);
      double requiredLow =
          MonteCarloCashFlow.project(config.investment(), factor.withValue(base, low), medianPath)
              .requiredPrice(config.targetIrrAnnual());
      double requiredHigh =
          MonteCarloCashFlow.project(config.investment(), factor.withValue(base, high), medianPath)
              .requiredPrice(config.targetIrrAnnual());
      result.add(
          new Sensitivity(
              factor.input,
              low,
              high,
              null,
              null,
              requiredLow,
              requiredHigh,
              Math.abs(requiredHigh - requiredLow)));
    }
    if (config.investment().pricingMode() == PricingMode.FOLLOW_MARKET) {
      double requiredLow =
          MonteCarloCashFlow.project(config.investment(), base, quantilePath(paths, indices, 0.10))
              .requiredPrice(config.targetIrrAnnual());
      double requiredHigh =
          MonteCarloCashFlow.project(config.investment(), base, quantilePath(paths, indices, 0.90))
              .requiredPrice(config.targetIrrAnnual());
      result.add(
          new Sensitivity(
              "marketPath",
              null,
              null,
              "P10",
              "P90",
              requiredLow,
              requiredHigh,
              Math.abs(requiredHigh - requiredLow)));
    }
    result.sort(Comparator.comparingDouble(Sensitivity::swing).reversed());
    return List.copyOf(result);
  }

  private static double[] quantilePath(double[][] paths, int[] indices, double percentile) {
    double[] quantiles = new double[paths[0].length];
    double[] prices = new double[indices.length];
    for (int month = 0; month < quantiles.length; month++) {
      for (int i = 0; i < indices.length; i++) {
        prices[i] = paths[indices[i]][month];
      }
      Arrays.sort(prices);
      quantiles[month] = quantile(prices, percentile);
    }
    return quantiles;
  }

  private static ScenarioInputs[] drawInputs(MonteCarloConfig config, int count) {
    var random = new SplittableRandom(config.simulation().seed() ^ INPUT_STREAM_SALT);
    var values = new ScenarioInputs[count];
    Uncertain uncertain = config.uncertain();
    for (int i = 0; i < count; i++) {
      values[i] =
          new ScenarioInputs(
              draw(uncertain.utilization(), random),
              draw(uncertain.capexOverrun(), random),
              draw(uncertain.opexPerGpuMonth(), random),
              draw(uncertain.opexAnnualInflation(), random));
    }
    return values;
  }

  private static double draw(List<Double> range, SplittableRandom random) {
    return MonteCarloMarket.triangular(range.get(0), range.get(1), range.get(2), random);
  }

  private static ScenarioInputs baseInputs(Uncertain uncertain) {
    return new ScenarioInputs(
        uncertain.utilization().get(1),
        uncertain.capexOverrun().get(1),
        uncertain.opexPerGpuMonth().get(1),
        uncertain.opexAnnualInflation().get(1));
  }

  public record ScenarioInputs(
      double utilization,
      double capexOverrun,
      double opexPerGpuMonth,
      double opexAnnualInflation) {}

  private record SelectedPaths(int[] indices, SelectionResult summary) {}

  public record SelectionResult(
      Scenario scenario,
      Double marketPrice12m,
      double centerQuantile,
      double lowerQuantile,
      double upperQuantile,
      double lowerPrice12m,
      double upperPrice12m,
      int nSelected,
      List<String> warnings) {}

  public record RequiredQuantiles(double p50, double p70, double p80, double p90) {}

  public record Candidate(
      double price,
      double probabilityMeetTarget,
      Double irrP10,
      Double irrP50,
      Double irrP90,
      int irrSampleSize,
      int irrUndefinedCount) {}

  public record ScenarioComparison(
      Scenario scenario,
      Double medianMarketPrice12m,
      Double requiredP50,
      Double requiredP80,
      int nSelected) {}

  public record Sensitivity(
      String input,
      Double lowValue,
      Double highValue,
      String lowLabel,
      String highLabel,
      double requiredAtLow,
      double requiredAtHigh,
      double swing) {}

  public record PricingResult(
      SelectionResult selection,
      RequiredQuantiles requiredQuantiles,
      List<Candidate> candidates,
      double baseRequired,
      List<Sensitivity> sensitivity,
      List<ScenarioComparison> scenarioComparison) {}

  private enum Factor {
    UTILIZATION("utilization"),
    CAPEX_OVERRUN("capexOverrun"),
    OPEX_PER_GPU_MONTH("opexPerGpuMonth"),
    OPEX_ANNUAL_INFLATION("opexAnnualInflation");

    private final String input;

    Factor(String input) {
      this.input = input;
    }

    List<Double> range(Uncertain uncertain) {
      return switch (this) {
        case UTILIZATION -> uncertain.utilization();
        case CAPEX_OVERRUN -> uncertain.capexOverrun();
        case OPEX_PER_GPU_MONTH -> uncertain.opexPerGpuMonth();
        case OPEX_ANNUAL_INFLATION -> uncertain.opexAnnualInflation();
      };
    }

    ScenarioInputs withValue(ScenarioInputs base, double value) {
      return switch (this) {
        case UTILIZATION ->
            new ScenarioInputs(
                value, base.capexOverrun(), base.opexPerGpuMonth(), base.opexAnnualInflation());
        case CAPEX_OVERRUN ->
            new ScenarioInputs(
                base.utilization(), value, base.opexPerGpuMonth(), base.opexAnnualInflation());
        case OPEX_PER_GPU_MONTH ->
            new ScenarioInputs(
                base.utilization(), base.capexOverrun(), value, base.opexAnnualInflation());
        case OPEX_ANNUAL_INFLATION ->
            new ScenarioInputs(
                base.utilization(), base.capexOverrun(), base.opexPerGpuMonth(), value);
      };
    }
  }
}
