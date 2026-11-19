package com.gpumarket;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("deploy")
@TestPropertySource(
    properties = {
      "RDS_JDBC_URL=jdbc:h2:mem:deployprofile;MODE=PostgreSQL",
      "RDS_USERNAME=sa",
      "RDS_PASSWORD=",
      "DATABASE_URL=jdbc:h2:mem:wrong",
      "DATABASE_USER=wrong",
      "DATABASE_PASSWORD=wrong"
    })
class DeployDatasourceProfileTest {
  @Autowired DataSource dataSource;

  @Test
  void deployUsesRdsVariablesRatherThanLocalVariables() throws Exception {
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getURL())
          .contains("deployprofile")
          .doesNotContain("wrong");
    }
  }
}
