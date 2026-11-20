package com.gpumarket.domain.simulation.montecarlo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class MonteCarloMarketTest {
  @Test
  void loadsThePrototypeHistoryWithoutChangingItsEvidence() {
    var history = MonteCarloMarket.loadSampleHistory();

    assertThat(history.points()).hasSize(18);
    assertThat(history.lastPrice()).isEqualTo(4.31);
    assertThat(history.returns()).hasSize(17);
    assertThat(history.evidence().annualizedTrend()).isCloseTo(-0.22637410503095867, within(1e-12));
    assertThat(history.evidence().worstMonth()).isEqualTo("2026-04");
    assertThat(history.historyHash())
        .isEqualTo("d044b9f51464420500765796fc9d741f3e141458c105c731a6f59cd4a5a12dd7");
  }

  @Test
  void matchesPythonPercentilesAndIncludesTiesAtTheCurrentPrice() {
    double[][] paths = {{1, 0.75}, {1, 1}, {1, 1}, {1, 2}};

    var monthly = MonteCarloMarket.marketRanges(paths, 8, 1).get(0);

    assertThat(monthly.month()).isEqualTo(1);
    assertThat(monthly.p10()).isCloseTo(6.6, within(1e-12));
    assertThat(monthly.p25()).isEqualTo(7.5);
    assertThat(monthly.p50()).isEqualTo(8);
    assertThat(monthly.p75()).isEqualTo(10);
    assertThat(monthly.p90()).isCloseTo(13.6, within(1e-12));
    assertThat(monthly.probabilityAtOrBelowCurrent()).isEqualTo(0.75);
  }

  @Test
  void matchesPythonWhenBootstrapAndJumpDrawsAreShared() {
    var simulation = new MonteCarloConfig.Simulation(1, 0, 2.0, 0.5, List.of(-0.3, -0.1, 0.0));
    var draws = List.of(0.8, 0.7, 0.1, 0.65, 0.2, 0.6, 0.9, 0.4, 0.95).iterator();
    var indices = List.of(2, 1).iterator();
    RandomGenerator random =
        new RandomGenerator() {
          @Override
          public long nextLong() {
            throw new AssertionError("Expected an explicit uniform draw or index");
          }

          @Override
          public double nextDouble() {
            return draws.next();
          }

          @Override
          public int nextInt(int bound) {
            assertThat(bound).isEqualTo(3);
            return indices.next();
          }
        };

    // Python price_paths.simulate_index_paths with the same scripted draws and returns.
    double[] python = {
      1, 1.030454533953517, 1.0220804538755284, 0.9434991742947721, 0.9345785796822326
    };
    double[][] paths =
        MonteCarloMarket.generatePaths(
            simulation,
            4,
            MonteCarloConfig.Post12Trend.KEEP,
            new double[] {0.1, -0.08, 0.03},
            random);

    assertThat(paths.length).isEqualTo(1);
    assertThat(paths[0]).containsExactly(python, within(1e-12));
    assertThat(draws.hasNext()).isFalse();
    assertThat(indices.hasNext()).isFalse();
  }

  @Test
  void trendChangesOnlyAfterTheAnchorMonthEvenWithPriceJumps() {
    var simulation = new MonteCarloConfig.Simulation(20, 123, 3.0, 0.5, List.of(-0.2, -0.1, 0.0));
    double[] returns = {0.071, -0.011, 0.003};

    var keep =
        MonteCarloMarket.generatePaths(simulation, 24, MonteCarloConfig.Post12Trend.KEEP, returns);
    var ease =
        MonteCarloMarket.generatePaths(simulation, 24, MonteCarloConfig.Post12Trend.EASE, returns);
    var flat =
        MonteCarloMarket.generatePaths(simulation, 24, MonteCarloConfig.Post12Trend.FLAT, returns);

    for (int path = 0; path < keep.length; path++) {
      assertThat(keep[path][0]).isEqualTo(1);
      for (int month = 0; month <= 12; month++) {
        assertThat(ease[path][month]).isEqualTo(keep[path][month]);
        assertThat(flat[path][month]).isEqualTo(keep[path][month]);
      }
      assertThat(keep[path][24]).isGreaterThan(ease[path][24]);
      assertThat(ease[path][24]).isGreaterThan(flat[path][24]);
    }
  }
}
