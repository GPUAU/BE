package com.gpumarket.domain.market.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketOfferRepository {
  private static final String SELECT =
      """
SELECT f.*, p.sku, p.original_product_name, p.original_model_name, p.system_type,
       p.service_option, p.gpu_count, p.vram_gb_per_gpu, p.vcpu_count, p.ram_gb,
       p.spec_scope, p.storage_description, p.intra_node_interconnect,
       p.interconnect_status, p.inter_node_network, p.region_code, p.country_code,
       p.configuration_version, v.provider_id, v.provider_name,
       CASE v.provider_type WHEN 'hyperscaler' THEN 'GENERAL_CLOUD'
            WHEN 'gpu_specialist' THEN 'GPU_SPECIALIST' ELSE 'UNKNOWN' END AS provider_type,
       g.gpu_model_id, g.model_name, g.generation,
       o.observation_id, o.check_id AS observation_check_id, o.collected_at, o.effective_from,
       o.price_certainty, o.raw_price_text, o.checked_at AS confirmed_at,
       n.normalization_id, n.nominal_usd_gpu_hour, n.effective_usd_gpu_hour,
       n.result_status, n.reason AS normalization_reason, n.rule_version, n.monthly_hours,
       n.formula_description, n.precision_policy, n.calculation_inputs,
       c.check_id, c.checked_at, c.processing_status, c.reason,
       d.check_id AS disclosure_check_id, d.checked_at AS disclosure_checked_at,
       d.processing_status AS disclosure_processing_status, d.disclosure_status
FROM offer f
JOIN product p ON p.product_id = f.product_id
JOIN provider v ON v.provider_id = p.provider_id
JOIN gpu_model g ON g.gpu_model_id = p.gpu_model_id
LEFT JOIN LATERAL (
  SELECT obs.*, verified.checked_at, verified.disclosure_status, observation_target.source_id
  FROM price_observation obs
  JOIN collection_check verified ON verified.check_id = obs.check_id
  JOIN collection_target observation_target ON observation_target.target_id = verified.target_id
  WHERE obs.offer_id = f.offer_id AND verified.processing_status = 'success'
    AND obs.collected_at <= :asOf AND verified.checked_at <= :asOf
    AND (obs.effective_from IS NULL OR obs.effective_from <= :asOf)
  ORDER BY obs.collected_at DESC, obs.observation_id DESC
  LIMIT 1
) o ON true
LEFT JOIN LATERAL (
  SELECT pn.*, r.rule_version, r.monthly_hours, r.formula_description, r.precision_policy,
         count(*) OVER () AS versions
  FROM price_normalization pn
  JOIN normalization_rule r ON r.rule_id = pn.rule_id
  WHERE pn.observation_id = o.observation_id
) n ON n.versions = 1
LEFT JOIN LATERAL (
  SELECT inspection.check_id, inspection.checked_at, inspection.processing_status,
         inspection.disclosure_status, inspection.reason
  FROM collection_check inspection
  WHERE inspection.offer_id = f.offer_id AND inspection.checked_at <= :asOf
  ORDER BY inspection.checked_at DESC, inspection.check_id DESC
  LIMIT 1
) c ON true
LEFT JOIN LATERAL (
  SELECT candidate.*
  FROM (
    (SELECT inspection.check_id, inspection.checked_at, inspection.processing_status,
            inspection.disclosure_status
     FROM collection_check inspection
     WHERE inspection.offer_id = f.offer_id AND inspection.checked_at <= :asOf
       AND inspection.processing_status = 'success' AND inspection.disclosure_status = 'public'
     ORDER BY inspection.checked_at DESC, inspection.check_id DESC
     LIMIT 1)
    UNION ALL
    (SELECT inspection.check_id, inspection.checked_at, inspection.processing_status,
            inspection.disclosure_status
     FROM collection_target target
     JOIN collection_check inspection ON inspection.target_id = target.target_id
     WHERE target.provider_id = p.provider_id AND target.gpu_model_id = p.gpu_model_id
       AND target.source_id = o.source_id
       AND (inspection.check_scope = 'target' OR inspection.offer_id = f.offer_id)
       AND inspection.scope_completed
       AND inspection.checked_at > o.checked_at AND inspection.checked_at <= :asOf
       AND (inspection.processing_status = 'not_found'
            OR (inspection.processing_status = 'success'
                AND inspection.disclosure_status IN ('not_disclosed', 'contact_sales',
                                                     'login_required')))
       AND EXISTS (
         SELECT 1 FROM source_evidence evidence
         WHERE evidence.check_id = inspection.check_id AND evidence.source_id = target.source_id
           AND evidence.evidence_role = 'official' AND evidence.primary_verified IS TRUE
           AND jsonb_path_exists(
             CASE WHEN pg_input_is_valid(evidence.excerpt, 'jsonb')
                  THEN evidence.excerpt::jsonb END,
             'strict $.offerAvailability ? (@.type() == "object" && @.offerId == $offerId
                && @.validFromEpoch <= $instant && @.validToEpoch > $instant)',
             jsonb_build_object('offerId', f.offer_id::text,
                                'instant', extract(epoch FROM CAST(:asOf AS timestamptz))),
             true)
       )
     ORDER BY inspection.checked_at DESC, inspection.check_id DESC
     LIMIT 1)
  ) candidate
  ORDER BY candidate.checked_at DESC, candidate.check_id DESC
  LIMIT 1
) d ON true
WHERE true
""";

  private final NamedParameterJdbcTemplate jdbc;

  public MarketOfferRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> offers(Map<String, Object> parameters) {
    var sql = new StringBuilder(SELECT);
    // Only these fixed fragments can enter the SQL. All filter values are bound parameters.
    append(sql, parameters, "offerId", "f.offer_id = :offerId");
    append(sql, parameters, "productId", "p.product_id = :productId");
    append(sql, parameters, "afterId", "f.offer_id > :afterId");
    append(sql, parameters, "modelId", "g.gpu_model_id = :modelId");
    append(sql, parameters, "generation", "lower(g.generation) = lower(:generation)");
    append(sql, parameters, "providerIds", "v.provider_id IN (:providerIds)");
    append(sql, parameters, "providerType", "coalesce(v.provider_type, '') IN (:providerType)");
    append(sql, parameters, "countryCode", "lower(p.country_code) = lower(:countryCode)");
    append(sql, parameters, "regionCode", "lower(p.region_code) = lower(:regionCode)");
    append(sql, parameters, "systemType", "lower(p.system_type) = lower(:systemType)");
    append(sql, parameters, "serviceOption", "lower(p.service_option) = lower(:serviceOption)");
    append(sql, parameters, "gpuCount", "p.gpu_count = :gpuCount");
    append(sql, parameters, "contractType", "lower(f.contract_type) = lower(:contractType)");
    append(sql, parameters, "commitmentMonths", "f.commitment_months = :commitmentMonths");
    append(
        sql,
        parameters,
        "contractProductType",
        "lower(f.contract_product_type) = lower(:contractProductType)");
    append(
        sql,
        parameters,
        "intraNodeInterconnect",
        "lower(p.intra_node_interconnect) = lower(:intraNodeInterconnect)");
    append(
        sql,
        parameters,
        "paymentOption",
        """
        coalesce(lower(f.payment_option), CASE WHEN f.contract_type IN ('spot', 'on_demand')
             THEN 'not_applicable' ELSE 'unknown' END) = lower(:paymentOption)
        """);
    append(sql, parameters, "publicPricesOnly", "o.disclosure_status = 'public'");
    sql.append(" ORDER BY f.offer_id");
    if (parameters.containsKey("pageSize")) {
      sql.append(" LIMIT :pageSize");
    }
    return jdbc.queryForList(sql.toString(), parameters);
  }

  public boolean productExists(long productId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM product WHERE product_id = :productId)",
            Map.of("productId", productId),
            Boolean.class));
  }

  public List<Map<String, Object>> evidence(List<Long> checkIds) {
    if (checkIds.isEmpty()) {
      return List.of();
    }
    return jdbc.queryForList(
        """
        SELECT e.*, d.source_name
        FROM source_evidence e JOIN data_source d ON d.source_id = e.source_id
        WHERE e.check_id IN (:ids) ORDER BY e.evidence_id
        """,
        Map.of("ids", checkIds));
  }

  public List<Map<String, Object>> publicPriceEvidence(List<Long> checkIds, OffsetDateTime asOf) {
    if (checkIds.isEmpty()) {
      return List.of();
    }
    return jdbc.queryForList(
        """
        SELECT e.*, d.source_name
        FROM source_evidence e JOIN data_source d ON d.source_id = e.source_id
        WHERE e.check_id IN (:ids) AND e.captured_at <= :asOf
          AND (e.verified_at IS NULL OR e.verified_at <= :asOf)
          AND (
            (e.evidence_role = 'official' AND d.source_type IN ('official_api', 'official_page'))
            OR (e.evidence_role = 'aggregator' AND d.source_type = 'aggregator'
                AND e.primary_verified IS TRUE AND e.verified_at <= :asOf)
          )
        ORDER BY e.evidence_id
        """,
        Map.of("ids", checkIds, "asOf", asOf));
  }

  public List<Map<String, Object>> components(List<Long> observationIds) {
    if (observationIds.isEmpty()) {
      return List.of();
    }
    return jdbc.queryForList(
        """
        SELECT * FROM price_component WHERE observation_id IN (:ids) ORDER BY component_id
        """,
        Map.of("ids", observationIds));
  }

  public List<Map<String, Object>> specEvidence(long productId) {
    return jdbc.queryForList(
        """
        SELECT pe.attribute_name, e.*, d.source_name
        FROM product_evidence pe JOIN source_evidence e ON e.evidence_id = pe.evidence_id
        JOIN data_source d ON d.source_id = e.source_id
        WHERE pe.product_id = :id ORDER BY pe.attribute_name, e.evidence_id
        """,
        Map.of("id", productId));
  }

  private static void append(
      StringBuilder sql, Map<String, Object> params, String name, String condition) {
    if (params.containsKey(name)) {
      sql.append(" AND ").append(condition);
    }
  }

  public List<Map<String, Object>> comparablePriceEvidence(
      List<Long> checkIds, OffsetDateTime asOf) {
    if (checkIds.isEmpty()) return List.of();
    return jdbc.queryForList(
        """
        SELECT e.*, d.source_name
        FROM source_evidence e
        JOIN data_source d ON d.source_id = e.source_id
        JOIN collection_check c ON c.check_id = e.check_id
        WHERE e.check_id IN (:ids) AND c.disclosure_status = 'public'
          AND e.captured_at <= :asOf
          AND (e.verified_at IS NULL OR e.verified_at <= :asOf)
          AND ((e.evidence_role = 'official' AND d.source_type IN ('official_api', 'official_page'))
            OR (e.evidence_role = 'aggregator' AND d.source_type = 'aggregator'
                AND e.primary_verified IS TRUE AND e.verified_at <= :asOf))
        ORDER BY e.evidence_id
        """,
        Map.of("ids", checkIds, "asOf", asOf));
  }
}
