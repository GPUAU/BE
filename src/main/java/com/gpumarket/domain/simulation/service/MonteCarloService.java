package com.gpumarket.domain.simulation.service;

import com.gpumarket.domain.simulation.dto.MonteCarloForecastRequestDto;
import com.gpumarket.domain.simulation.dto.MonteCarloPricingRequestDto;
import com.gpumarket.domain.simulation.dto.MonteCarloResponseDtos.Forecast;
import com.gpumarket.domain.simulation.dto.MonteCarloResponseDtos.Metadata;
import com.gpumarket.domain.simulation.dto.MonteCarloResponseDtos.Pricing;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Post12Trend;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Simulation;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket.MarketHistory;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloPricing;
import com.gpumarket.global.api.InvalidRequestException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class MonteCarloService {
  private static final int FORECAST_MONTHS = 12;
  private static final List<String> SAMPLE_WARNINGS =
      List.of(
          "B300 가상 가격 이력으로 계산한 시뮬레이션입니다.",
          "가격 이력은 18개월이며 실제 가격을 이용한 백테스트는 수행하지 않았습니다.",
          "분위수와 목표 IRR 달성 비율은 입력 가정과 생성된 표본을 기준으로 합니다.");

  private final MarketHistory history = MonteCarloMarket.loadSampleHistory();
  private final Semaphore running = new Semaphore(1);

  public Forecast forecast(MonteCarloForecastRequestDto request) {
    var simulation = request.simulation();
    validateSimulation(simulation);

    return calculate(
        () -> {
          double[][] paths =
              MonteCarloMarket.generatePaths(
                  simulation, request.analysisMonths(), Post12Trend.KEEP, history.returns());
          return new Forecast(
              metadata(simulation, request.analysisMonths()),
              history.evidence(),
              history.points(),
              MonteCarloMarket.marketRanges(paths, history.lastPrice(), FORECAST_MONTHS));
        });
  }

  public Pricing pricing(MonteCarloPricingRequestDto request) {
    var config = request.config();
    var simulation = config.simulation();
    var investment = config.investment();
    var uncertain = config.uncertain();
    var selection = request.selection();

    validateSimulation(simulation);
    range("capexPerGpu", investment.capexPerGpu(), 1, 10_000_000);
    range("residualValuePct", investment.residualValuePct(), 0, 1);
    if (investment.rampMonths() > investment.months()) {
      throw new InvalidRequestException("rampMonths는 분석 기간 이하여야 합니다.");
    }
    range("targetIrrAnnual", config.targetIrrAnnual(), 0, 2);
    config.candidatePrices().forEach(price -> range("candidatePrices", price, 0.01, 100_000));
    triangle("utilization", uncertain.utilization(), 0.01, 1);
    triangle("capexOverrun", uncertain.capexOverrun(), 0, 2);
    triangle("opexPerGpuMonth", uncertain.opexPerGpuMonth(), 0, 1_000_000);
    triangle("opexAnnualInflation", uncertain.opexAnnualInflation(), -0.5, 1);

    if ((selection.scenario() == null) == (selection.marketPrice12m() == null)) {
      throw new InvalidRequestException("scenario 또는 marketPrice12m 중 하나만 입력하세요.");
    }
    if (selection.marketPrice12m() != null) {
      range("marketPrice12m", selection.marketPrice12m(), 0.01, 100_000);
    }

    return calculate(
        () -> {
          double[][] paths =
              MonteCarloMarket.generatePaths(
                  simulation, investment.months(), selection.post12Trend(), history.returns());
          return new Pricing(
              metadata(simulation, investment.months()),
              selection.post12Trend(),
              MonteCarloMarket.marketRanges(paths, history.lastPrice(), investment.months()),
              MonteCarloPricing.calculate(config, selection, paths, history.lastPrice()));
        });
  }

  private static void validateSimulation(Simulation simulation) {
    range("meanBlockLength", simulation.meanBlockLength(), 1, 24);
    range("jumpProbPerMonth", simulation.jumpProbPerMonth(), 0, 1);
    triangle("jumpSizeTri", simulation.jumpSizeTri(), -0.95, 0);
  }

  private static void triangle(String field, List<Double> values, double min, double max) {
    for (double value : values) {
      range(field, value, min, max);
    }
    if (values.get(0) > values.get(1) || values.get(1) > values.get(2)) {
      throw new InvalidRequestException(field + "는 최소값, 최빈값, 최대값 순서여야 합니다.");
    }
  }

  private static void range(String field, double value, double min, double max) {
    if (!Double.isFinite(value) || value < min || value > max) {
      throw new InvalidRequestException(field + "의 허용 범위는 " + min + "~" + max + "입니다.");
    }
  }

  private Metadata metadata(Simulation simulation, int months) {
    return new Metadata(
        "B300",
        true,
        "b300-sample-2026-11",
        LocalDate.of(2026, 11, 1),
        history.historyHash(),
        "java-monte-carlo-v1",
        "USD/GPU-hour",
        730,
        simulation.seed(),
        simulation.nPaths(),
        months,
        SAMPLE_WARNINGS);
  }

  private <T> T calculate(Supplier<T> calculation) {
    if (!running.tryAcquire()) {
      throw new CalculationInProgress();
    }

    try {
      return calculation.get();
    } finally {
      running.release();
    }
  }

  public static class CalculationInProgress extends RuntimeException {}
}
