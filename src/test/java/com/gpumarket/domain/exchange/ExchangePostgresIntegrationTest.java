package com.gpumarket.domain.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpumarket.domain.exchange.repository.ExchangeRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class ExchangePostgresIntegrationTest {
  @Test
  void queriesPostgresUsingSessionTemporaryTables() {
    var ds =
        new SingleConnectionDataSource(
            System.getenv("TEST_POSTGRES_URL"), System.getenv("TEST_POSTGRES_USER"), "", true);
    try {
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute(
          "CREATE TEMP TABLE data_source (source_id BIGINT, source_name TEXT, base_url TEXT)");
      jdbc.execute(
          "CREATE TEMP TABLE exchange_rate (exchange_rate_id BIGINT, source_id BIGINT,"
              + " base_currency TEXT, quote_currency TEXT, rate NUMERIC, reference_date DATE,"
              + " collected_at TIMESTAMPTZ, source_url TEXT)");
      jdbc.execute("CREATE TEMP TABLE normalization_rule (rule_id BIGINT, rule_version TEXT)");
      jdbc.execute(
          "CREATE TEMP TABLE price_normalization (normalization_id BIGINT, observation_id BIGINT,"
              + " rule_id BIGINT, nominal_usd_gpu_hour NUMERIC, effective_usd_gpu_hour NUMERIC,"
              + " result_status TEXT)");
      jdbc.update("INSERT INTO data_source VALUES (1,'수출입은행','https://www.koreaexim.go.kr')");
      jdbc.update(
          "INSERT INTO exchange_rate VALUES (1,1,'USD','KRW',1300,DATE '2026-11-16',TIMESTAMPTZ"
              + " '2026-11-16 10:00:00+00',NULL)");
      jdbc.update("INSERT INTO normalization_rule VALUES (1,'v1')");
      jdbc.update("INSERT INTO price_normalization VALUES (8,5,1,3.5,NULL,'success')");
      var repository = new ExchangeRepository(jdbc);
      assertThat(repository.latestRate(LocalDate.parse("2026-11-18")).orElseThrow().rate())
          .isEqualByComparingTo("1300");
      assertThat(repository.normalization(8).orElseThrow().observationId()).isEqualTo(5);
    } finally {
      ds.destroy();
    }
  }
}
