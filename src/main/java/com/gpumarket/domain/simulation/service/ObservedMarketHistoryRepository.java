package com.gpumarket.domain.simulation.service;

import com.gpumarket.domain.simulation.montecarlo.ObservedHistorySelector;
import com.gpumarket.domain.simulation.montecarlo.ObservedHistorySelector.Observation;
import com.gpumarket.global.api.InvalidRequestException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads one offer's monthly observations, including missing normalization and evidence. */
@Repository
public class ObservedMarketHistoryRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public ObservedMarketHistoryRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ObservedHistorySelector.Selection find(long modelId, long offerId, Instant now) {
    Instant start =
        YearMonth.from(now.atZone(ZoneOffset.UTC))
            .minusMonths(12)
            .atDay(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
    var rows =
        jdbc.query(
            """
SELECT o.observation_id, o.collected_at, CASE WHEN o.price_certainty = 'firm' THEN n.effective_usd_gpu_hour END AS effective_usd_gpu_hour,
       n.rule_version, ev.source_id, ev.evidence_id
FROM price_observation o
JOIN offer f ON f.offer_id = o.offer_id
JOIN product p ON p.product_id = f.product_id
LEFT JOIN LATERAL (
  SELECT pn.effective_usd_gpu_hour, pn.result_status, nr.rule_version,
         count(*) OVER () AS versions
  FROM price_normalization pn JOIN normalization_rule nr ON nr.rule_id = pn.rule_id
  WHERE pn.observation_id = o.observation_id
) n ON n.versions = 1 AND n.result_status = 'success'
LEFT JOIN LATERAL (
  SELECT e.source_id, e.evidence_id FROM source_evidence e
  JOIN data_source d ON d.source_id = e.source_id
  WHERE e.check_id = o.check_id AND e.captured_at <= :end
    AND (e.verified_at IS NULL OR e.verified_at <= :end)
    AND ((e.evidence_role = 'official' AND d.source_type IN ('official_api', 'official_page'))
      OR (e.evidence_role = 'aggregator' AND d.source_type = 'aggregator'
          AND e.primary_verified IS TRUE AND e.verified_at <= :end))
  ORDER BY e.evidence_id LIMIT 1
) ev ON true
WHERE p.gpu_model_id = :modelId AND o.offer_id = :offerId
  AND o.corrects_observation_id IS NULL
  AND o.collected_at >= :start AND o.collected_at < :end
ORDER BY o.collected_at, o.observation_id
""",
            Map.of(
                "modelId",
                modelId,
                "offerId",
                offerId,
                "start",
                java.sql.Timestamp.from(start),
                "end",
                java.sql.Timestamp.from(now)),
            (rs, row) ->
                new Observation(
                    rs.getLong("observation_id"),
                    rs.getTimestamp("collected_at").toInstant(),
                    rs.getObject("effective_usd_gpu_hour") == null
                        ? null
                        : rs.getDouble("effective_usd_gpu_hour"),
                    rs.getString("rule_version"),
                    rs.getObject("source_id") == null ? null : rs.getLong("source_id"),
                    rs.getObject("evidence_id") == null ? null : rs.getLong("evidence_id")));
    try {
      return ObservedHistorySelector.select(rows, now);
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException(exception.getMessage());
    }
  }
}
