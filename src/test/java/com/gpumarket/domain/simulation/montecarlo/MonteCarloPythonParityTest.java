package com.gpumarket.domain.simulation.montecarlo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Scenario;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Selection;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloPricing.PricingResult;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloPricing.ScenarioInputs;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonteCarloPythonParityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final double TOLERANCE = 1e-9;
  private static final Map<String, String> SENSITIVITY_KEYS =
      Map.of(
          "utilization", "utilization",
          "capex_overrun", "capexOverrun",
          "opex_per_gpu_month", "opexPerGpuMonth",
          "opex_annual_inflation", "opexAnnualInflation",
          "market_path", "marketPath");
  private static final Map<String, Scenario> SCENARIOS =
      Map.of("보수", Scenario.CONSERVATIVE, "기준", Scenario.BASELINE, "낙관", Scenario.OPTIMISTIC);

  @Test
  void matchesEveryMonthlyPriceBandGivenTheSamePythonPaths() throws IOException {
    var fixture = readFixture();
    var paths = JSON.treeToValue(fixture.required("paths"), double[][].class);
    var expected = fixture.required("monthlyRanges");
    var actual =
        MonteCarloMarket.marketRanges(paths, fixture.required("lastPrice").doubleValue(), 12);

    assertThat(actual).hasSize(expected.size()).hasSize(12);
    for (int index = 0; index < actual.size(); index++) {
      var month = actual.get(index);
      var reference = expected.get(index);
      assertThat(month.month()).isEqualTo(reference.required("month").intValue());
      assertClose(month.p10(), reference.required("p10"));
      assertClose(month.p25(), reference.required("p25"));
      assertClose(month.p50(), reference.required("p50"));
      assertClose(month.p75(), reference.required("p75"));
      assertClose(month.p90(), reference.required("p90"));
      assertThat(month.probabilityAtOrBelowCurrent())
          .isEqualTo(reference.required("prob_below_now").doubleValue());
    }
  }

  @Test
  void matchesPythonPricingGivenTheSamePathsAndInvestmentSamples() throws IOException {
    var fixture = readFixture();
    var paths = JSON.treeToValue(fixture.required("paths"), double[][].class);
    var lastPrice = fixture.required("lastPrice").doubleValue();
    var samples = JSON.treeToValue(fixture.required("sampleInputs"), ScenarioInputs[].class);

    for (JsonNode testCase : fixture.required("pricingCases")) {
      var config = JSON.treeToValue(testCase.required("config"), MonteCarloConfig.class);
      var selection = JSON.treeToValue(testCase.required("selection"), Selection.class);
      var expected = testCase.required("expected");
      var actual = MonteCarloPricing.calculate(config, selection, paths, lastPrice, samples);

      assertSelectionMatches(actual, expected.required("selection"));
      var quantiles = expected.required("requiredQuantiles");
      assertClose(actual.requiredQuantiles().p50(), quantiles.required("0.5"));
      assertClose(actual.requiredQuantiles().p70(), quantiles.required("0.7"));
      assertClose(actual.requiredQuantiles().p80(), quantiles.required("0.8"));
      assertClose(actual.requiredQuantiles().p90(), quantiles.required("0.9"));
      assertClose(actual.baseRequired(), expected.required("baseRequired"));
      assertCandidatesMatch(actual, expected.required("candidates"));
      assertSensitivityMatches(actual, expected.required("sensitivity"));
      assertScenarioComparisonMatches(actual, expected.required("scenarioComparison"));
    }
  }

  private static void assertSelectionMatches(PricingResult actual, JsonNode expected) {
    var selection = actual.selection();
    assertThat(selection.nSelected()).isEqualTo(expected.required("n_selected").intValue());
    assertThat(selection.warnings()).hasSize(expected.required("warnings").size());
    assertClose(selection.centerQuantile(), expected.required("center_q"));
    assertClose(selection.lowerQuantile(), expected.required("band_q").get(0));
    assertClose(selection.upperQuantile(), expected.required("band_q").get(1));
    assertClose(selection.lowerPrice12m(), expected.required("band_price_12m").get(0));
    assertClose(selection.upperPrice12m(), expected.required("band_price_12m").get(1));
  }

  private static void assertCandidatesMatch(PricingResult actual, JsonNode expected) {
    assertThat(actual.candidates()).hasSize(expected.size());
    for (int index = 0; index < expected.size(); index++) {
      var candidate = actual.candidates().get(index);
      var reference = expected.get(index);
      assertThat(candidate.price()).isEqualTo(reference.required("price").doubleValue());
      assertThat(candidate.probabilityMeetTarget())
          .isEqualTo(reference.required("prob_meet_target").doubleValue());
      assertClose(candidate.irrP10(), reference.required("irr_p10"));
      assertClose(candidate.irrP50(), reference.required("irr_p50"));
      assertClose(candidate.irrP90(), reference.required("irr_p90"));
      assertThat(candidate.irrSampleSize())
          .isEqualTo(reference.required("irr_sample_size").intValue());
      assertThat(candidate.irrUndefinedCount())
          .isEqualTo(reference.required("irr_undefined_count").intValue());
    }
  }

  private static void assertSensitivityMatches(PricingResult actual, JsonNode expected) {
    assertThat(actual.sensitivity()).hasSize(expected.size());
    for (JsonNode reference : expected) {
      var key = SENSITIVITY_KEYS.get(reference.required("input").textValue());
      assertThat(actual.sensitivity())
          .filteredOn(row -> row.input().equals(key))
          .singleElement()
          .satisfies(
              row -> {
                assertClose(row.requiredAtLow(), reference.required("required_at_low"));
                assertClose(row.requiredAtHigh(), reference.required("required_at_high"));
                assertClose(row.swing(), reference.required("swing"));
              });
    }
  }

  private static void assertScenarioComparisonMatches(PricingResult actual, JsonNode expected) {
    assertThat(actual.scenarioComparison()).hasSize(expected.size());
    for (int index = 0; index < expected.size(); index++) {
      var reference = expected.get(index);
      var row = actual.scenarioComparison().get(index);
      assertThat(row.scenario())
          .isEqualTo(SCENARIOS.get(reference.required("scenario").textValue()));
      assertClose(row.medianMarketPrice12m(), reference.required("price_12m"));
      assertClose(row.requiredP50(), reference.required("req_p50"));
      assertClose(row.requiredP80(), reference.required("req_p80"));
      assertThat(row.nSelected()).isEqualTo(reference.required("n_selected").intValue());
    }
  }

  private static void assertClose(Double actual, JsonNode reference) {
    if (reference.isNull()) {
      assertThat(actual).isNull();
    } else {
      assertThat(actual).isCloseTo(reference.doubleValue(), within(TOLERANCE));
    }
  }

  private static JsonNode readFixture() throws IOException {
    try (var fixture =
        MonteCarloPythonParityTest.class.getResourceAsStream("/montecarlo/python-parity.json")) {
      return JSON.readTree(fixture);
    }
  }
}
