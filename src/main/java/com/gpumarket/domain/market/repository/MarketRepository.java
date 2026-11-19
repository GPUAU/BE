package com.gpumarket.domain.market.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketRepository {
  private final JdbcTemplate jdbc;

  public MarketRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> observation(long id) {
    return jdbc.queryForList(
        "SELECT o.observation_id, o.offer_id, f.product_id, o.check_id, o.collected_at,"
            + " o.effective_from, o.price_certainty, o.raw_price_text, o.original_currency,"
            + " o.original_time_unit, o.price_applies_to, o.corrects_observation_id,"
            + " o.correction_reason FROM price_observation o JOIN offer f ON f.offer_id=o.offer_id"
            + " WHERE o.observation_id=?",
        id);
  }

  public List<Map<String, Object>> components(long id) {
    return jdbc.queryForList(
        "SELECT component_id, component_type, amount, currency, time_unit, applies_to,"
            + " discount_rate, raw_value FROM price_component WHERE observation_id=? ORDER BY"
            + " component_id",
        id);
  }

  public List<Map<String, Object>> evidence(long checkId) {
    return jdbc.queryForList(
        "SELECT e.evidence_id, e.source_id, e.evidence_role, e.source_url, e.source_version,"
            + " e.excerpt, e.primary_verified, e.verified_at, e.captured_at, s.source_name FROM"
            + " source_evidence e JOIN data_source s ON e.source_id=s.source_id WHERE e.check_id=?"
            + " ORDER BY e.evidence_id",
        checkId);
  }

  public List<Map<String, Object>> normalizations(long observationId) {
    return jdbc.queryForList(
        "SELECT n.normalization_id, n.observation_id, n.nominal_usd_gpu_hour,"
            + " n.effective_usd_gpu_hour, n.result_status, n.reason, n.calculation_inputs,"
            + " r.rule_version, r.monthly_hours, r.formula_description, r.precision_policy FROM"
            + " price_normalization n JOIN normalization_rule r ON r.rule_id=n.rule_id WHERE"
            + " n.observation_id=? ORDER BY n.normalization_id",
        observationId);
  }
}
