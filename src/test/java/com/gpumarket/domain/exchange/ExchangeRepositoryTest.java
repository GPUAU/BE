package com.gpumarket.domain.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpumarket.domain.exchange.repository.ExchangeRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ExchangeRepositoryTest {
  JdbcTemplate jdbc;
  ExchangeRepository repository;

  @BeforeEach
  void setup() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:fx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("DROP ALL OBJECTS");
    jdbc.execute(
        "CREATE TABLE data_source (source_id BIGINT PRIMARY KEY, source_name TEXT, base_url TEXT)");
    jdbc.execute(
        "CREATE TABLE exchange_rate (exchange_rate_id BIGINT PRIMARY KEY, source_id BIGINT,"
            + " base_currency TEXT, quote_currency TEXT, rate NUMERIC, reference_date DATE,"
            + " collected_at TIMESTAMP WITH TIME ZONE, source_url TEXT)");
    jdbc.execute("CREATE TABLE normalization_rule (rule_id BIGINT PRIMARY KEY, rule_version TEXT)");
    jdbc.execute(
        "CREATE TABLE price_normalization (normalization_id BIGINT PRIMARY KEY, observation_id"
            + " BIGINT, rule_id BIGINT, nominal_usd_gpu_hour NUMERIC, effective_usd_gpu_hour"
            + " NUMERIC, result_status TEXT)");
    repository = new ExchangeRepository(jdbc);
  }

  @Test
  void selectsLatestPermittedSourceAndExcludesFutureAndOtherSources() {
    jdbc.update(
        "INSERT INTO data_source VALUES (1,'수출입은행','https://www.koreaexim.go.kr'), (2,'다른"
            + " 출처','https://example.invalid')");
    jdbc.update(
        "INSERT INTO exchange_rate VALUES (1,1,'USD','KRW',1300,DATE '2026-11-16',TIMESTAMP WITH"
            + " TIME ZONE '2026-11-16 10:00:00+00',NULL), (2,1,'USD','KRW',1400,DATE"
            + " '2026-11-19',TIMESTAMP WITH TIME ZONE '2026-11-19 10:00:00+00',NULL),"
            + " (3,2,'USD','KRW',1500,DATE '2026-11-18',TIMESTAMP WITH TIME ZONE '2026-11-18"
            + " 10:00:00+00',NULL)");
    var result = repository.latestRate(LocalDate.parse("2026-11-18")).orElseThrow();
    assertThat(result.id()).isEqualTo(1);
    assertThat(result.date()).isEqualTo(LocalDate.parse("2026-11-16"));
  }

  @Test
  void retrievesNormalizationWithRuleAndObservation() {
    jdbc.update("INSERT INTO normalization_rule VALUES (1,'v1')");
    jdbc.update("INSERT INTO price_normalization VALUES (8,5,1,3.5,NULL,'success')");
    var result = repository.normalization(8).orElseThrow();
    assertThat(result.observationId()).isEqualTo(5);
    assertThat(result.ruleVersion()).isEqualTo("v1");
  }
}
