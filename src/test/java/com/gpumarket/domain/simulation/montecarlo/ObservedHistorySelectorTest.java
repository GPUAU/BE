package com.gpumarket.domain.simulation.montecarlo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ObservedHistorySelectorTest {
  private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

  private ArrayList<ObservedHistorySelector.Observation> observations() {
    var rows = new ArrayList<ObservedHistorySelector.Observation>();
    for (int i = 0; i < 12; i++) {
      var month = YearMonth.of(2025, 11).plusMonths(i);
      rows.add(
          new ObservedHistorySelector.Observation(
              i + 1,
              month.atDay(15).atStartOfDay().toInstant(ZoneOffset.UTC),
              3.0 + i * 0.1,
              "v1",
              10L,
              20L));
    }
    return rows;
  }

  @Test
  void selectsLatestInEachCompletedMonthWithProvenance() {
    var rows = observations();
    rows.add(
        new ObservedHistorySelector.Observation(
            99, Instant.parse("2026-10-29T00:00:00Z"), 5.0, "v1", 11L, 21L));
    rows.add(
        new ObservedHistorySelector.Observation(
            100, Instant.parse("2026-11-01T00:00:00Z"), 7.0, "v1", 12L, 22L));
    var selected = ObservedHistorySelector.select(rows, NOW);
    assertThat(selected.observations()).hasSize(12);
    assertThat(selected.observations().get(11).observationId()).isEqualTo(99);
    assertThat(selected.history().lastPrice()).isEqualTo(5.0);
  }

  @Test
  void rejectsMissingMonthAndDifferentRules() {
    var missing = observations();
    missing.remove(5);
    assertThatThrownBy(() -> ObservedHistorySelector.select(missing, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    var mixed = observations();
    var last = mixed.get(11);
    mixed.set(
        11,
        new ObservedHistorySelector.Observation(
            last.observationId(), last.collectedAt(), last.price(), "v2", 10L, 20L));
    assertThatThrownBy(() -> ObservedHistorySelector.select(mixed, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingEvidenceInsteadOfSilentlyUsingOlderPrice() {
    var rows = observations();
    rows.add(
        new ObservedHistorySelector.Observation(
            99, Instant.parse("2026-10-29T00:00:00Z"), 5.0, "v1", null, null));
    assertThatThrownBy(() -> ObservedHistorySelector.select(rows, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
