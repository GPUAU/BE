package com.gpumarket.domain.simulation.service;

import com.gpumarket.domain.simulation.dto.MonteCarloForecastRequestDto;
import com.gpumarket.domain.simulation.dto.MonteCarloPricingRequestDto;
import com.gpumarket.domain.simulation.dto.MonteCarloResponseDtos.Forecast;
import com.gpumarket.domain.simulation.dto.MonteCarloResponseDtos.Metadata;
import com.gpumarket.domain.simulation.dto.MonteCarloResponseDtos.Pricing;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Post12Trend;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Simulation;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloJson;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloJson.StrictFields;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloMarket.MarketHistory;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloPricing;
import com.gpumarket.global.api.InvalidRequestException;
import java.time.Instant;
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

  private final MarketHistory sampleHistory = MonteCarloMarket.loadSampleHistory();
  private final ObservedMarketHistoryRepository observedRepository;

  public MonteCarloService(ObservedMarketHistoryRepository observedRepository) {
    this.observedRepository = observedRepository;
  }

  private record SelectedHistory(
      MarketHistory history,
      java.util.List<com.gpumarket.domain.simulation.montecarlo.ObservedHistorySelector.Observation>
          observations,
      com.gpumarket.domain.simulation.dto.ObservedHistorySource source) {}

  private SelectedHistory select(
      com.gpumarket.domain.simulation.dto.ObservedHistorySource source, Instant now) {
    if (source == null) return new SelectedHistory(sampleHistory, List.of(), null);
    var result = observedRepository.find(source.modelId(), source.offerId(), now);
    return new SelectedHistory(result.history(), result.observations(), source);
  }

  private final Semaphore running = new Semaphore(1);

  public Forecast forecast(MonteCarloForecastRequestDto request) {
    var simulation = request.simulation();
    validateSimulation(simulation);

    return calculate(
        () -> {
          Instant now = Instant.now();
          var selected = select(request.historySource(), now);
          var history = selected.history();
          Metadata metadata =
              metadata(request, simulation, request.analysisMonths(), selected, now);
          double[][] paths =
              MonteCarloMarket.generatePaths(
                  simulation, request.analysisMonths(), Post12Trend.KEEP, history.returns());
          return new Forecast(
              metadata,
              history.evidence(),
              history.points(),
              MonteCarloMarket.marketRanges(paths, history.lastPrice(), FORECAST_MONTHS),
              selected.observations());
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
          Instant now = Instant.now();
          var selected = select(request.historySource(), now);
          var history = selected.history();
          Metadata metadata = metadata(request, simulation, investment.months(), selected, now);
          double[][] paths =
              MonteCarloMarket.generatePaths(
                  simulation, investment.months(), selection.post12Trend(), history.returns());
          return new Pricing(
              metadata,
              selection.post12Trend(),
              MonteCarloMarket.marketRanges(paths, history.lastPrice(), investment.months()),
              MonteCarloPricing.calculate(config, selection, paths, history.lastPrice()),
              selected.observations());
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

  private Metadata metadata(
      StrictFields request,
      Simulation simulation,
      int months,
      SelectedHistory selected,
      Instant runAt) {
    boolean sample = selected.source() == null;
    return new Metadata(
        sample ? "B300" : "model-" + selected.source().modelId(),
        sample,
        sample ? "b300-sample-2026-11" : "observed-offer-" + selected.source().offerId(),
        sample
            ? LocalDate.of(2026, 11, 1)
            : selected
                .observations()
                .get(selected.observations().size() - 1)
                .collectedAt()
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate(),
        selected.history().historyHash(),
        MonteCarloJson.configHash(request),
        runAt,
        "java-monte-carlo-v2",
        "USD/GPU-hour",
        730,
        simulation.seed(),
        simulation.nPaths(),
        months,
        sample
            ? SAMPLE_WARNINGS
            : List.of(
                "동일 오퍼의 최근 연속 12개 완료 월 관측값을 사용한 시뮬레이션입니다.",
                "진행 중인 월은 제외하므로 예측 기준 가격이 최신 관측가격과 다를 수 있습니다.",
                "미래 가격은 확정값이 아니며 예측 정확도는 검증되지 않았습니다."));
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
