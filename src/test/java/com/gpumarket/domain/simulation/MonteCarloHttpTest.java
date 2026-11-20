package com.gpumarket.domain.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
