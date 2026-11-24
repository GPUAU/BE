package com.gpumarket.global;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "RDS_JDBC_URL=jdbc:h2:mem:managementDeploy;MODE=PostgreSQL",
      "RDS_USERNAME=sa",
      "RDS_PASSWORD=",
      "management.server.port=0"
    })
@ActiveProfiles("deploy")
@AutoConfigureObservability(tracing = false) // 테스트는 기본으로 지표 export를 끈다
class ManagementEndpointDeployTest {
  @Autowired TestRestTemplate http;
  @LocalServerPort int apiPort;
  @LocalManagementPort int managementPort;

  private String management(String path) {
    return "http://localhost:" + managementPort + path;
  }

  @Test
  void exposesHealthAndPrometheusOnManagementPortOnly() {
    assertThat(http.getForEntity(management("/actuator/health"), String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    var metrics = http.getForEntity(management("/actuator/prometheus"), String.class);
    assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(metrics.getBody()).contains("jvm_memory_used_bytes");
  }

  @Test
  void keepsOtherActuatorEndpointsAndApiPortClosed() {
    assertThat(http.getForEntity(management("/actuator/env"), String.class).getStatusCode())
        .isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    assertThat(
            http.getForEntity("http://localhost:" + apiPort + "/actuator/prometheus", String.class)
                .getStatusCode())
        .isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
  }
}
