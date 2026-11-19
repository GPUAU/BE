package com.gpumarket.domain.exchange.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExchangeRepository {
  public record Normalization(
      long observationId,
      String ruleVersion,
      BigDecimal nominal,
      BigDecimal effective,
      String status) {}

  public record Rate(
      long id,
      long sourceId,
      String sourceName,
      String url,
      BigDecimal rate,
      LocalDate date,
      Instant collectedAt) {}

  private final JdbcTemplate jdbc;

  public ExchangeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Normalization> normalization(long id) {
    return jdbc
        .query(
            "SELECT n.observation_id, r.rule_version, n.nominal_usd_gpu_hour,"
                + " n.effective_usd_gpu_hour, n.result_status FROM price_normalization n JOIN"
                + " normalization_rule r ON r.rule_id=n.rule_id WHERE n.normalization_id=?",
            (rs, row) ->
                new Normalization(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getBigDecimal(3),
                    rs.getBigDecimal(4),
                    rs.getString(5)),
            id)
        .stream()
        .findFirst();
  }

  public Optional<Rate> latestRate(LocalDate date) {
    return jdbc
        .query(
            "SELECT e.exchange_rate_id, s.source_id, s.source_name,"
                + " COALESCE(e.source_url,s.base_url), e.rate, e.reference_date, e.collected_at"
                + " FROM exchange_rate e JOIN data_source s ON s.source_id=e.source_id WHERE"
                + " e.base_currency='USD' AND e.quote_currency='KRW' AND e.reference_date<=? AND"
                + " (LOWER(s.base_url) LIKE '%koreaexim.go.kr%' OR LOWER(e.source_url) LIKE"
                + " '%koreaexim.go.kr%') ORDER BY e.reference_date DESC, e.collected_at DESC,"
                + " e.exchange_rate_id DESC LIMIT 1",
            (rs, row) ->
                new Rate(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBigDecimal(5),
                    rs.getObject(6, LocalDate.class),
                    rs.getObject(7, OffsetDateTime.class).toInstant()),
            date)
        .stream()
        .findFirst();
  }
}
