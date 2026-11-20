package com.gpumarket.domain.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "RDS_JDBC_URL=jdbc:h2:mem:montecarloDeploy;MODE=PostgreSQL",
      "RDS_USERNAME=sa",
      "RDS_PASSWORD="
    })
@ActiveProfiles({"local", "deploy"})
class MonteCarloDeployHttpTest {
  @Autowired TestRestTemplate http;

  @Test
  void deployPolicyTakesPrecedenceAndBlocksBothSimulationRequests() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var request = new HttpEntity<>("{}", headers);

    for (String path :
        new String[] {"/api/v1/monte-carlo-forecasts", "/api/v1/monte-carlo-pricing-simulations"}) {
      assertThat(http.postForEntity(path, request, String.class).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
  }
}
