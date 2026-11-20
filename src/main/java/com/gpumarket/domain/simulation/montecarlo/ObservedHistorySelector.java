package com.gpumarket.domain.simulation.montecarlo;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Selects each completed UTC month's last observation without filling gaps. */
public final class ObservedHistorySelector {
  private ObservedHistorySelector() {}

  public record Observation(
      long observationId,
      Instant collectedAt,
      Double price,
      String ruleVersion,
      Long sourceId,
      Long evidenceId) {}

  public record Selection(MonteCarloMarket.MarketHistory history, List<Observation> observations) {
    public Selection {
      observations = List.copyOf(observations);
    }
  }

  public static Selection select(List<Observation> observations, Instant now) {
    YearMonth current = YearMonth.from(now.atZone(ZoneOffset.UTC));
    Map<YearMonth, Observation> months = new TreeMap<>();
    for (Observation observation : observations) {
      if (observation.collectedAt() == null || observation.collectedAt().isAfter(now)) continue;
      YearMonth month = YearMonth.from(observation.collectedAt().atZone(ZoneOffset.UTC));
      if (!month.isBefore(current)) continue;
      months.merge(
          month,
          observation,
          (left, right) ->
              Comparator.comparing(Observation::collectedAt)
                          .thenComparingLong(Observation::observationId)
                          .compare(left, right)
                      >= 0
                  ? left
                  : right);
    }
    var chosen = new java.util.ArrayList<Observation>();
    var points = new java.util.ArrayList<MonteCarloMarket.HistoryPoint>();
    String rule = null;
    for (int i = 12; i >= 1; i--) {
      YearMonth month = current.minusMonths(i);
      Observation row = months.get(month);
      if (row == null) {
        throw new IllegalArgumentException(month + "의 관측 이력이 없습니다. 연속 12개월이 필요합니다.");
      }
      if (row.price() == null
          || !Double.isFinite(row.price())
          || row.price() <= 0
          || row.ruleVersion() == null) {
        throw new IllegalArgumentException(month + "의 확정가 또는 환산 결과를 확인할 수 없습니다.");
      }
      if (row.sourceId() == null || row.evidenceId() == null) {
        throw new IllegalArgumentException(month + "의 검증된 가격 출처를 확인할 수 없습니다.");
      }
      if (rule != null && !rule.equals(row.ruleVersion())) {
        throw new IllegalArgumentException("월별 가격의 환산 규칙이 일치하지 않습니다.");
      }
      rule = row.ruleVersion();
      chosen.add(row);
      points.add(new MonteCarloMarket.HistoryPoint(month.toString(), row.price()));
    }
    return new Selection(MonteCarloMarket.fromObservedHistory(points), chosen);
  }
}
