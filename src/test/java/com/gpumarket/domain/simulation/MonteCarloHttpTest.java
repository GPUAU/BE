package com.gpumarket.domain.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "DATABASE_URL=jdbc:h2:mem:montecarlo;MODE=PostgreSQL",
      "DATABASE_USER=sa",
      "DATABASE_PASSWORD="
    })
@ActiveProfiles("local")
class MonteCarloHttpTest {
  private static final String FORECAST = "/api/v1/monte-carlo-forecasts";
  private static final String PRICING = "/api/v1/monte-carlo-pricing-simulations";
  private static final String SIMULATION =
      """
      {"nPaths":1000,"seed":20261118,"meanBlockLength":3,"jumpProbPerMonth":0.03,
       "jumpSizeTri":[-0.30,-0.15,-0.05]}
      """;
  private static final String INVESTMENT =
      """
      {"gpus":64,"months":12,"capexPerGpu":55000,"rampMonths":3,
       "residualValuePct":0.10,"pricingMode":"FOLLOW_MARKET"}
      """;
  private static final String UNCERTAIN =
      """
      {"utilization":[0.55,0.75,0.90],"capexOverrun":[0.00,0.03,0.12],
       "opexPerGpuMonth":[700,900,1200],"opexAnnualInflation":[0.01,0.03,0.06]}
      """;

  @Autowired TestRestTemplate http;

  @org.springframework.boot.test.mock.mockito.MockBean
  com.gpumarket.domain.simulation.service.ObservedMarketHistoryRepository observedRepository;

  @Test
  void observedRequestsUseSelectedHistoryForBothApis() {
    var rows =
        new java.util.ArrayList<
            com.gpumarket.domain.simulation.montecarlo.ObservedHistorySelector.Observation>();
    var current = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
    for (int i = 12; i >= 1; i--) {
      var at = current.minusMonths(i).atDay(10).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
      rows.add(
          new com.gpumarket.domain.simulation.montecarlo.ObservedHistorySelector.Observation(
              13 - i, at, 3.0 + (12 - i) * .1, "v1", 2L, 3L));
    }
    var selected =
        com.gpumarket.domain.simulation.montecarlo.ObservedHistorySelector.select(
            rows, java.time.Instant.now());
    org.mockito.Mockito.when(
            observedRepository.find(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(selected);
    String source = "\"historySource\":{\"modelId\":1,\"offerId\":2}";
    var forecast =
        post(FORECAST, "{\"simulation\":" + SIMULATION + ",\"analysisMonths\":12," + source + "}");
    assertThat(forecast.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(forecast.getBody().path("result").path("metadata").path("isSample").asBoolean())
        .isFalse();
    assertThat(forecast.getBody().path("result").path("observations").size()).isEqualTo(12);
    var pricing =
        post(
            PRICING,
            pricing("{\"scenario\":\"BASELINE\",\"post12Trend\":\"EASE\"}")
                .replaceFirst("\\}$", "," + source + "}"));
    assertThat(pricing.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pricing.getBody().path("result").path("metadata").path("isSample").asBoolean())
        .isFalse();
    assertThat(pricing.getBody().path("result").path("observations").size()).isEqualTo(12);
  }

  @Test
  void forecastsTwelveMonthsAndReportsReproducibleSampleMetadata() {
    String request = "{\"simulation\":" + SIMULATION + ",\"analysisMonths\":12}";
    var response = post(FORECAST, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = response.getBody();
    assertThat(body.path("isSuccess").asBoolean()).isTrue();
    JsonNode result = body.path("result");
    assertThat(result.path("metadata").path("isSample").asBoolean()).isTrue();
    assertThat(result.path("metadata").path("seed").asInt()).isEqualTo(20261118);
    assertThat(result.path("metadata").path("historyHash").asText()).hasSize(64);
    assertThat(result.path("history").size()).isEqualTo(18);
    assertThat(result.path("monthly").size()).isEqualTo(12);
    assertThat(result.path("monthly").get(11).path("p50").asDouble()).isPositive();

    assertThat(post(FORECAST, request).getBody().path("result").path("monthly"))
        .isEqualTo(result.path("monthly"));
  }

  @Test
  void forecastHashIdentifiesInputsIndependentlyOfJsonFormatting() {
    String original = "{\"simulation\":" + SIMULATION + ",\"analysisMonths\":12}";
    String equivalent =
        "{\"analysisMonths\":12,\"simulation\":"
            + SIMULATION.replace("\"meanBlockLength\":3", "\"meanBlockLength\":3.00")
            + "}";
    String hash = calculationHash(FORECAST, original);

    assertThat(hash).isEqualTo("4441166166e6cceb98fc91b467daa8cf5365ccfcc27ee7f3ad72441edaababdb");
    assertThat(calculationHash(FORECAST, equivalent)).isEqualTo(hash);
    assertThat(calculationHash(FORECAST, original.replace("0.03", "0.04"))).isNotEqualTo(hash);
    assertThat(
            calculationHash(
                FORECAST, original.replace("\"analysisMonths\":12", "\"analysisMonths\":24")))
        .isNotEqualTo(hash);
  }

  @Test
  void pricingHashIncludesInvestmentAssumptionsAndMarketSelection() {
    String original = pricing("{\"scenario\":\"BASELINE\",\"post12Trend\":\"EASE\"}");
    String equivalent =
        pricing("{\"post12Trend\":\"EASE\",\"marketPrice12m\":null,\"scenario\":\"BASELINE\"}")
            .replace("[0.00,0.03,0.12]", "[-0.0,0.03,0.12]")
            .replace("\"capexPerGpu\":55000", "\"capexPerGpu\":5.5e4");
    String hash = calculationHash(PRICING, original);

    assertThat(calculationHash(PRICING, equivalent)).isEqualTo(hash);
    assertThat(calculationHash(PRICING, original.replace("55000", "56000"))).isNotEqualTo(hash);
    assertThat(calculationHash(PRICING, original.replace("BASELINE", "OPTIMISTIC")))
        .isNotEqualTo(hash);
  }

  private String calculationHash(String endpoint, String request) {
    Instant before = Instant.now();
    var response = post(endpoint, request);
    Instant after = Instant.now();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode metadata = response.getBody().path("result").path("metadata");
    String runAt = metadata.path("runAt").asText();
    assertThat(runAt).endsWith("Z");
    assertThat(Instant.parse(runAt)).isBetween(before, after);
    String hash = metadata.path("configHash").asText();
    assertThat(hash).matches("[0-9a-f]{64}");
    return hash;
  }

  @Test
  void pricesSelectedPathsAndReturnsCandidateIrrs() {
    var response = post(PRICING, pricing("{\"scenario\":\"BASELINE\",\"post12Trend\":\"EASE\"}"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode result = response.getBody().path("result");
    JsonNode pricing = result.path("pricing");
    int selected = pricing.path("selection").path("nSelected").asInt();
    assertThat(selected).isBetween(1, 1000);
    assertThat(pricing.path("requiredQuantiles").path("p50").asDouble()).isPositive();
    assertThat(pricing.path("requiredQuantiles").path("p80").asDouble())
        .isGreaterThanOrEqualTo(pricing.path("requiredQuantiles").path("p50").asDouble());
    assertThat(pricing.path("candidates").size()).isEqualTo(2);
    assertThat(pricing.path("candidates").get(0).path("irrSampleSize").asInt()).isEqualTo(selected);
    assertThat(result.path("monthly").size()).isEqualTo(12);
  }

  @Test
  void requiresExactlyOneMarketSelection() {
    for (String selection :
        new String[] {
          "{\"post12Trend\":\"KEEP\"}",
          "{\"scenario\":\"BASELINE\",\"marketPrice12m\":4.80,\"post12Trend\":\"KEEP\"}"
        }) {
      var response = post(PRICING, pricing(selection));

      assertThat(response.getStatusCode().value()).isEqualTo(422);
      assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
  }

  @Test
  void acceptsDirectMarketPriceAsNumber() {
    var response = post(PRICING, pricing("{\"marketPrice12m\":4.8,\"post12Trend\":\"EASE\"}"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            response
                .getBody()
                .path("result")
                .path("pricing")
                .path("selection")
                .path("marketPrice12m")
                .asDouble())
        .isEqualTo(4.8);
  }

  @Test
  void rejectsExcessivePathsAndFractionalIntegerInputs() {
    var excessive =
        post(
            FORECAST,
            "{\"simulation\":" + SIMULATION.replace("1000", "50001") + ",\"analysisMonths\":12}");
    var fractional = post(FORECAST, "{\"simulation\":" + SIMULATION + ",\"analysisMonths\":12.5}");

    assertThat(excessive.getStatusCode().value()).isEqualTo(422);
    assertThat(excessive.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(fractional.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(fractional.getBody().path("code").asText()).isEqualTo("INVALID_REQUEST");
  }

  @Test
  void rejectsNumericSelectionEnums() {
    assertMalformed(PRICING, pricing("{\"scenario\":1,\"post12Trend\":\"EASE\"}"));
    assertMalformed(PRICING, pricing("{\"scenario\":\"1\",\"post12Trend\":\"EASE\"}"));
    assertMalformed(PRICING, pricing("{\"scenario\":\"BASELINE\",\"post12Trend\":1}"));
    assertMalformed(
        PRICING,
        pricing("{\"scenario\":\"BASELINE\",\"post12Trend\":\"EASE\"}")
            .replace("\"FOLLOW_MARKET\"", "1"));
  }

  @Test
  void rejectsStringEncodedNumbersInBothRequests() {
    String simulation = SIMULATION.replace("\"meanBlockLength\":3", "\"meanBlockLength\":\"3\"");
    assertMalformed(FORECAST, "{\"simulation\":" + simulation + ",\"analysisMonths\":12}");
    assertMalformed(
        PRICING,
        pricing("{\"scenario\":\"BASELINE\",\"post12Trend\":\"EASE\"}")
            .replace("[6,8]", "[\"6\",8]"));
    assertMalformed(PRICING, pricing("{\"marketPrice12m\":\"4.8\",\"post12Trend\":\"EASE\"}"));
  }

  @Test
  void rejectsUnknownFieldsThatCouldSelectAnUnsupportedModelOrDataSource() {
    assertMalformed(
        FORECAST,
        "{\"simulation\":" + SIMULATION + ",\"analysisMonths\":12,\"modelCode\":\"H100\"}");
    assertMalformed(
        PRICING,
        pricing("{\"scenario\":\"BASELINE\",\"post12Trend\":\"EASE\"}")
            .replace("\"targetIrrAnnual\":0.15", "\"targetIrrAnnual\":0.15,\"isSample\":false"));
  }

  private void assertMalformed(String path, String request) {
    var response = post(path, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_REQUEST");
  }

  private static String pricing(String selection) {
    return """
    {"config":{"simulation":%s,"investment":%s,"uncertain":%s,
                "targetIrrAnnual":0.15,"candidatePrices":[6,8]},
     "selection":%s}
    """
        .formatted(SIMULATION, INVESTMENT, UNCERTAIN, selection);
  }

  private ResponseEntity<JsonNode> post(String path, String body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
  }
}
