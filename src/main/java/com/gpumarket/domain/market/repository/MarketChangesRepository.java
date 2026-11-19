package com.gpumarket.domain.market.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketChangesRepository {
  private static final String EVENTS =
      """
WITH corrections AS MATERIALIZED (
  SELECT correction.corrects_observation_id
  FROM price_observation correction
  JOIN collection_check check_row ON check_row.check_id = correction.check_id
  WHERE correction.corrects_observation_id IS NOT NULL
    AND correction.collected_at <= :asOf
    AND check_row.checked_at <= :asOf AND check_row.processing_status = 'success'
), observed AS (
  SELECT cur.observation_id AS current_id, cur.offer_id, cur.check_id AS current_check_id,
         cur.collected_at AS current_at, cur.original_currency AS current_currency,
         cur.original_time_unit AS current_time_unit, cur.price_applies_to AS current_applies_to,
         prev.observation_id AS previous_id, prev.check_id AS previous_check_id,
         prev.collected_at AS previous_at, prev.original_currency AS previous_currency,
         prev.original_time_unit AS previous_time_unit, prev.price_applies_to AS previous_applies_to,
         cn.normalization_id AS current_normalization_id, cn.rule_version AS current_rule,
         cn.effective_usd_gpu_hour AS current_price,
         pn.normalization_id AS previous_normalization_id, pn.rule_version AS previous_rule,
         pn.effective_usd_gpu_hour AS previous_price,
         official.check_id AS undisclosed_check_id, official.checked_at AS undisclosed_at,
         CASE
           WHEN official.check_id IS NOT NULL THEN 'BECAME_PUBLIC'
           WHEN prev.observation_id IS NULL THEN 'FIRST_OBSERVATION'
           WHEN (prev.original_currency, prev.original_time_unit, prev.price_applies_to) IS NOT NULL
             AND (cur.original_currency, cur.original_time_unit, cur.price_applies_to) IS NOT NULL
           THEN CASE
             WHEN (prev.original_currency, prev.original_time_unit, prev.price_applies_to)
               <> (cur.original_currency, cur.original_time_unit, cur.price_applies_to)
               THEN 'UNIT_CHANGE'
             WHEN pn.rule_version = cn.rule_version
               AND pn.effective_usd_gpu_hour <> cn.effective_usd_gpu_hour
               THEN 'PRICE_CHANGE'
           END
         END AS event_type
  FROM product p JOIN offer f ON f.product_id = p.product_id
  JOIN price_observation cur ON cur.offer_id = f.offer_id
  JOIN collection_check current_check ON current_check.check_id = cur.check_id
  LEFT JOIN LATERAL (
    SELECT earlier.*
    FROM price_observation earlier
    JOIN collection_check earlier_check ON earlier_check.check_id = earlier.check_id
    WHERE earlier.offer_id = cur.offer_id AND earlier.price_certainty = 'firm'
      AND earlier.corrects_observation_id IS NULL
      AND (earlier.collected_at, earlier.observation_id) < (cur.collected_at, cur.observation_id)
      AND earlier_check.checked_at <= :asOf AND earlier_check.processing_status = 'success'
    ORDER BY earlier.collected_at DESC, earlier.observation_id DESC
    LIMIT 1
  ) prev ON true
  LEFT JOIN LATERAL (
    SELECT n.*, r.rule_version, count(*) OVER () AS versions
    FROM price_normalization n JOIN normalization_rule r ON r.rule_id = n.rule_id
    WHERE n.observation_id = cur.observation_id
  ) cn ON cn.versions = 1 AND cn.result_status = 'success'
  LEFT JOIN LATERAL (
    SELECT n.*, r.rule_version, count(*) OVER () AS versions
    FROM price_normalization n JOIN normalization_rule r ON r.rule_id = n.rule_id
    WHERE n.observation_id = prev.observation_id
  ) pn ON pn.versions = 1 AND pn.result_status = 'success'
  LEFT JOIN LATERAL (
    SELECT c.check_id, c.checked_at, c.disclosure_status
    FROM collection_check c
    WHERE current_check.disclosure_status = 'public'
      AND c.offer_id = cur.offer_id AND c.check_scope = 'offer' AND c.scope_completed
      AND c.processing_status = 'success' AND c.disclosure_status <> 'unknown'
      AND c.checked_at < cur.collected_at
      AND (prev.collected_at IS NULL OR c.checked_at > prev.collected_at)
      AND EXISTS (
        SELECT 1 FROM source_evidence e WHERE e.check_id = c.check_id
          AND e.evidence_role = 'official' AND e.primary_verified IS TRUE
      )
    ORDER BY c.checked_at DESC, c.check_id DESC
    LIMIT 1
  ) official ON official.disclosure_status = 'not_disclosed'
  WHERE p.gpu_model_id = :modelId
    AND cur.collected_at >= :start AND cur.collected_at < :end AND cur.collected_at <= :asOf
    AND current_check.checked_at <= :asOf AND current_check.processing_status = 'success'
    AND cur.price_certainty = 'firm' AND cur.corrects_observation_id IS NULL
    AND NOT EXISTS (SELECT 1 FROM corrections WHERE corrects_observation_id = cur.observation_id)
    AND NOT EXISTS (SELECT 1 FROM corrections WHERE corrects_observation_id = prev.observation_id)
""";

  private final NamedParameterJdbcTemplate jdbc;

  public MarketChangesRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> events(Map<String, Object> filters) {
    var sql = new StringBuilder(EVENTS);
    if (filters.containsKey("providerIds")) {
      sql.append(" AND p.provider_id IN (:providerIds)");
    }
    if (filters.containsKey("countryCode")) {
      sql.append(" AND lower(p.country_code) = :countryCode");
    }
    if (filters.containsKey("regionCode")) {
      sql.append(" AND lower(p.region_code) = :regionCode");
    }
    if (filters.containsKey("contractType")) {
      sql.append(" AND lower(f.contract_type) = :contractType");
    }
    sql.append(
        """
        ) SELECT * FROM observed WHERE event_type IS NOT NULL
          AND (current_at, current_id) > (:afterTime, :afterId)
        ORDER BY current_at, current_id LIMIT :size
        """);
    return jdbc.queryForList(sql.toString(), filters);
  }

  public List<Map<String, Object>> conversions(List<Long> normalizationIds, OffsetDateTime asOf) {
    if (normalizationIds.isEmpty()) {
      return List.of();
    }
    return jdbc.queryForList(
        """
SELECT k.normalization_id, e.exchange_rate_id, e.rate, e.reference_date,
       s.source_id, s.source_name, coalesce(e.source_url, s.base_url) AS source_url
FROM krw_conversion k JOIN exchange_rate e USING (exchange_rate_id)
JOIN data_source s USING (source_id)
JOIN price_normalization n USING (normalization_id)
JOIN price_observation o USING (observation_id)
WHERE k.normalization_id IN (:ids) AND k.calculated_at <= :asOf
  AND k.effective_krw_gpu_hour IS NOT NULL
  AND e.base_currency = 'USD' AND e.quote_currency = 'KRW' AND e.rate > 0
  AND e.reference_date <= (o.collected_at AT TIME ZONE 'Asia/Seoul')::date
  AND (lower(s.base_url) LIKE '%koreaexim.go.kr%' OR lower(e.source_url) LIKE '%koreaexim.go.kr%')
""",
        Map.of("ids", normalizationIds, "asOf", asOf));
  }
}
