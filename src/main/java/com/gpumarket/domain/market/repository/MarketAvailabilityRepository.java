package com.gpumarket.domain.market.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketAvailabilityRepository {
  private static final String TARGETS =
      """
      WITH targets AS (
        SELECT t.*,
               CASE lower(t.contract_filter)
                 WHEN 'savings_plans' THEN 'commitment'
                 WHEN 'reserved_instances' THEN 'commitment'
                 WHEN 'reservations' THEN 'commitment'
                 ELSE lower(t.contract_filter)
               END AS contract_type,
               EXISTS (
                 SELECT 1 FROM product p
                 WHERE p.provider_id = t.provider_id AND p.region_code = t.region_filter
                   AND lower(p.country_code) = :countryCode
               ) AS known_target_country
        FROM collection_target t
        WHERE t.gpu_model_id = :modelId AND t.target_id > :afterId
      """;

  private static final String AVAILABILITY =
      """
), page AS MATERIALIZED (
  SELECT t.* FROM targets t
  WHERE (CAST(:countryCode AS text) IS NULL AND CAST(:contractType AS text) IS NULL)
    OR ((CAST(:countryCode AS text) IS NULL OR t.known_target_country)
      AND (CAST(:contractType AS text) IS NULL OR t.contract_type = :contractType))
    OR (
      (CAST(:contractType AS text) IS NULL OR t.contract_type = :contractType
        OR t.contract_type IS NULL)
      AND EXISTS (
        SELECT 1 FROM collection_check c
        JOIN product p ON p.product_id = c.product_id
        LEFT JOIN offer f ON f.offer_id = c.offer_id
        WHERE c.target_id = t.target_id AND c.checked_at <= :asOf
          AND (CAST(:countryCode AS text) IS NULL OR lower(p.country_code) = :countryCode)
          AND (CAST(:contractType AS text) IS NULL OR t.contract_type = :contractType
            OR lower(f.contract_type) = :contractType)
      )
    )
  ORDER BY t.target_id LIMIT :size
), checks AS MATERIALIZED (
  SELECT c.* FROM page t
  JOIN collection_check c ON c.target_id = t.target_id AND c.checked_at <= :asOf
  LEFT JOIN product p ON p.product_id = c.product_id
  LEFT JOIN offer f ON f.offer_id = c.offer_id
  WHERE (CAST(:countryCode AS text) IS NULL
         OR lower(p.country_code) = :countryCode
         OR (p.product_id IS NULL AND t.known_target_country))
    AND (CAST(:contractType AS text) IS NULL
         OR lower(f.contract_type) = :contractType
         OR (f.offer_id IS NULL AND t.contract_type = :contractType))
), observed AS MATERIALIZED (
  SELECT o.*, c.target_id, c.product_id, c.checked_at
  FROM checks c JOIN price_observation o USING (check_id)
  WHERE c.processing_status = 'success' AND o.collected_at <= :asOf
    AND (o.effective_from IS NULL OR o.effective_from <= :asOf)
), last_observed AS (
  SELECT DISTINCT ON (target_id) * FROM observed
  ORDER BY target_id, collected_at DESC, observation_id DESC
), first_observed AS (
  SELECT target_id, min(collected_at) AS first_price_observed_at FROM observed
  GROUP BY target_id
), latest_check AS (
  SELECT DISTINCT ON (target_id) * FROM checks
  ORDER BY target_id, checked_at DESC, check_id DESC
), latest_disclosure AS (
  SELECT DISTINCT ON (c.target_id) c.check_id, c.target_id, c.checked_at, c.disclosure_status
  FROM checks c
  WHERE c.processing_status = 'success'
    AND c.disclosure_status IN ('public', 'not_disclosed', 'contact_sales', 'login_required')
    AND EXISTS (
      SELECT 1 FROM source_evidence e JOIN data_source s USING (source_id)
      WHERE e.check_id = c.check_id AND e.captured_at <= :asOf
        AND (
          (e.evidence_role = 'official' AND s.source_type IN ('official_api', 'official_page'))
          OR (e.evidence_role = 'aggregator' AND e.primary_verified IS TRUE
              AND e.verified_at <= :asOf)
        )
    )
  ORDER BY c.target_id, c.checked_at DESC, c.check_id DESC
)
SELECT t.target_id, t.provider_id, t.gpu_model_id, t.scope_description,
       coalesce(o.product_id, c.product_id) AS product_id,
       coalesce(o.offer_id, c.offer_id) AS offer_id,
       c.check_id, c.checked_at, c.processing_status, c.reason AS check_reason,
       d.check_id AS disclosure_check_id, d.checked_at AS disclosure_checked_at,
       d.disclosure_status, f.first_price_observed_at,
       o.observation_id, o.check_id AS observation_check_id, o.collected_at, o.effective_from,
       o.price_certainty, o.raw_price_text, o.checked_at AS confirmed_at,
       n.normalization_id, n.nominal_usd_gpu_hour, n.effective_usd_gpu_hour, n.result_status,
       n.reason AS normalization_reason, n.rule_version, n.monthly_hours
FROM page t
LEFT JOIN latest_check c USING (target_id)
LEFT JOIN latest_disclosure d USING (target_id)
LEFT JOIN last_observed o USING (target_id)
LEFT JOIN first_observed f USING (target_id)
LEFT JOIN LATERAL (
  SELECT n.*, r.rule_version, r.monthly_hours, count(*) OVER () AS versions
  FROM price_normalization n JOIN normalization_rule r USING (rule_id)
  WHERE n.observation_id = o.observation_id
) n ON n.versions = 1
ORDER BY t.target_id
""";

  private final NamedParameterJdbcTemplate jdbc;

  public MarketAvailabilityRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> find(Map<String, Object> params) {
    String providerFilter =
        params.containsKey("providerIds") ? " AND t.provider_id IN (:providerIds)\n" : "\n";
    return jdbc.queryForList(TARGETS + providerFilter + AVAILABILITY, params);
  }
}
