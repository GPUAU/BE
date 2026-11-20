package com.gpumarket.domain.simulation.montecarlo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservedHistoryTest {
  private List<MonteCarloMarket.HistoryPoint> history(int count) {
    var rows = new ArrayList<MonteCarloMarket.HistoryPoint>();
    for (int i = 0; i < count; i++) {
      rows.add(
          new MonteCarloMarket.HistoryPoint(
              YearMonth.of(2024, 1).plusMonths(i).toString(), 3.0 + i * .01));
    }
    return rows;
  }

  @Test
  void requiresTwelveConsecutiveClosedMonths() {
    assertThatThrownBy(() -> MonteCarloMarket.fromObservedHistory(history(11)))
        .isInstanceOf(IllegalArgumentException.class);
    var rows = history(12);
    rows.remove(5);
    rows.add(new MonteCarloMarket.HistoryPoint("2025-01", 5));
    assertThatThrownBy(() -> MonteCarloMarket.fromObservedHistory(rows))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnchangingPricesAndPreservesValidHistory() {
    var flat = new ArrayList<MonteCarloMarket.HistoryPoint>();
    for (var point : history(12)) flat.add(new MonteCarloMarket.HistoryPoint(point.month(), 3));
    assertThatThrownBy(() -> MonteCarloMarket.fromObservedHistory(flat))
        .isInstanceOf(IllegalArgumentException.class);
    var result = MonteCarloMarket.fromObservedHistory(history(12));
    assertThat(result.points()).hasSize(12);
    assertThat(result.returns()).hasSize(11);
  }
}
