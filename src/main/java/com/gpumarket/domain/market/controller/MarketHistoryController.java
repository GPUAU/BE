package com.gpumarket.domain.market.controller;

import com.gpumarket.global.api.ApiResponse;
import com.gpumarket.global.api.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MarketHistoryController {
  private static final int MAX_DAYS = 731;
  private static final int MAX_POINTS = 12_000;
  private static final int MAX_PAGE_SIZE = 100;
  private final NamedParameterJdbcTemplate jdbc;

  public MarketHistoryController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Operation(
      summary = "GPU 가격 이력 조회",
      description =
          "오퍼별 관측 이력. from 포함, to 미포함입니다. 최대 731일·12,000개 관측을 조회하며 limit과 cursor로 오퍼 단위 페이지를 조회할 수"
              + " 있습니다.",
      parameters = {
        @Parameter(
            name = "modelId",
            in = ParameterIn.QUERY,
            required = true,
            description = "GPU 모델 ID (B200: 2, GB300: 3)",
            example = "2"),
        @Parameter(
            name = "from",
            in = ParameterIn.QUERY,
            required = true,
            description = "조회 시작 시각 (포함)",
            example = "2026-11-16T00:00:00Z"),
        @Parameter(
            name = "to",
            in = ParameterIn.QUERY,
            required = true,
            description = "조회 종료 시각 (미포함)",
            example = "2026-11-18T00:00:00Z"),
        @Parameter(name = "limit", in = ParameterIn.QUERY, description = "페이지당 오퍼 수 (1~100)"),
        @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "이전 응답의 nextCursor")
      })
  @GetMapping("/api/v1/market/price-histories")
  public ApiResponse<Map<String, Object>> history(
      @Parameter(hidden = true) @RequestParam Map<String, String> params) {
    if (!params.keySet().stream()
        .allMatch(List.of("modelId", "from", "to", "limit", "cursor")::contains)) {
      throw new InvalidRequestException("지원하지 않는 가격 이력 조회 조건입니다.");
    }
    String model = params.get("modelId");
    String from = params.get("from");
    String to = params.get("to");

    if (model == null || from == null || to == null) {
      throw new InvalidRequestException("모델과 기간이 필요합니다.");
    }

    long modelId;
    OffsetDateTime start;
    OffsetDateTime end;

    try {
      modelId = Long.parseLong(model);
      start = OffsetDateTime.parse(from);
      end = OffsetDateTime.parse(to);
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new InvalidRequestException("모델 또는 기간 형식이 올바르지 않습니다.");
    }

    if (modelId < 1 || !start.isBefore(end)) {
      throw new InvalidRequestException("기간 또는 모델이 올바르지 않습니다.");
    }

    if (Duration.between(start, end).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
      throw new InvalidRequestException("가격 이력은 한 번에 최대 731일까지 조회할 수 있습니다.");
    }

    var query = new LinkedHashMap<String, Object>();
    query.put("modelId", modelId);
    query.put("start", start);
    query.put("end", end);
    query.put("pointLimit", MAX_POINTS + 1);
    Integer limit = params.containsKey("limit") ? historyLimit(params.get("limit")) : null;
    if (params.containsKey("cursor") && limit == null) {
      throw new InvalidRequestException("가격 이력 cursor를 사용하려면 limit이 필요합니다.");
    }

    String nextCursor = null;
    List<Map<String, Object>> rows;
    if (limit == null) {
      rows = historyRows(query, "");
    } else {
      query.put("afterOfferId", historyCursor(params.get("cursor")));
      query.put("seriesLimit", limit + 1);
      var page =
          jdbc.queryForList(
              """
              SELECT f.offer_id FROM offer f JOIN product p ON p.product_id = f.product_id
              WHERE p.gpu_model_id = :modelId AND f.offer_id > :afterOfferId
                AND EXISTS (SELECT 1 FROM price_observation o WHERE o.offer_id = f.offer_id
                            AND o.collected_at >= :start AND o.collected_at < :end)
              ORDER BY f.offer_id LIMIT :seriesLimit
              """,
              query,
              Long.class);
      var offerIds = page.subList(0, Math.min(limit, page.size()));
      if (page.size() > limit) {
        nextCursor = offerIds.get(offerIds.size() - 1).toString();
      }
      query.put("offerIds", offerIds);
      rows = offerIds.isEmpty() ? List.of() : historyRows(query, "AND o.offer_id IN (:offerIds)");
    }

    if (rows.size() > MAX_POINTS) {
      throw new InvalidRequestException("가격 이력이 12,000개를 초과했습니다. 조회 기간을 줄이거나 오퍼 페이지 크기를 줄여 주세요.");
    }

    // Read evidence only for the observations selected for this response.
    var checkIds =
        rows.stream().map(row -> row.get("check_id")).filter(Objects::nonNull).distinct().toList();
    var evidenceRows =
        checkIds.isEmpty()
            ? List.<Map<String, Object>>of()
            : jdbc.queryForList(
                """
                SELECT check_id, evidence_id, source_id FROM source_evidence
                WHERE check_id IN (:checkIds) ORDER BY check_id, evidence_id
                """,
                Map.of("checkIds", checkIds));
    Map<String, List<Map<String, Object>>> evidenceByCheck = new LinkedHashMap<>();
    for (var evidence : evidenceRows) {
      evidenceByCheck
          .computeIfAbsent(evidence.get("check_id").toString(), ignored -> new ArrayList<>())
          .add(evidence);
    }

    Map<String, Map<String, Object>> series = new LinkedHashMap<>();
    for (var r : rows) {
      String offer = r.get("offer_id").toString();
      if (!series.containsKey(offer)) {
        var s = new LinkedHashMap<String, Object>();
        s.put("seriesId", "offer-" + offer);
        s.put("offerId", offer);
        s.put("modelId", model);
        s.put("modelName", r.get("model_name"));
        s.put("providerId", r.get("provider_id").toString());
        s.put("providerName", r.get("provider_name"));
        s.put(
            "providerType",
            "hyperscaler".equals(r.get("provider_type"))
                ? "GENERAL_CLOUD"
                : "gpu_specialist".equals(r.get("provider_type")) ? "GPU_SPECIALIST" : "UNKNOWN");
        for (String key : List.of("system_type", "service_option", "contract_type"))
          s.put(camel(key), r.get(key) == null ? null : r.get(key).toString().toUpperCase());
        for (String key :
            List.of(
                "gpu_count", "country_code", "region_code", "commitment_months", "payment_option"))
          s.put(camel(key), r.get(key));
        s.put("points", new ArrayList<Map<String, Object>>());
        s.put("gaps", List.of());
        series.put(offer, s);
      }
      var point = new LinkedHashMap<String, Object>();
      point.put("offerId", offer);
      point.put("observationId", r.get("observation_id").toString());
      point.put("collectedAt", r.get("collected_at"));
      point.put("effectiveFrom", r.get("effective_from"));
      point.put("displayTimeBasis", "COLLECTED_AT");
      boolean usable =
          r.get("normalization_id") != null
              && "success".equals(r.get("result_status"))
              && r.get("effective_usd_gpu_hour") != null;
      point.put(
          "normalizationId",
          r.get("normalization_id") == null ? null : r.get("normalization_id").toString());
      point.put("ruleVersion", r.get("rule_version"));
      point.put("effectiveUsdGpuHour", usable ? r.get("effective_usd_gpu_hour").toString() : null);
      point.put("status", usable ? "OK" : "UNAVAILABLE");
      point.put(
          "reason",
          usable
              ? null
              : (r.get("normalization_reason") != null
                  ? r.get("normalization_reason")
                  : "환산 결과가 없거나 여러 버전이 있어 선택할 수 없습니다."));
      List<Map<String, Object>> ev =
          r.get("check_id") == null
              ? List.of()
              : evidenceByCheck.getOrDefault(r.get("check_id").toString(), List.of());
      point.put("evidenceIds", ev.stream().map(e -> e.get("evidence_id").toString()).toList());
      point.put(
          "sourceIds", ev.stream().map(e -> e.get("source_id").toString()).distinct().toList());
      @SuppressWarnings("unchecked")
      var points = (List<Map<String, Object>>) series.get(offer).get("points");
      points.add(point);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "appliedFilters",
        Map.of("modelId", model, "from", from, "to", to, "seriesBy", "OFFER", "currency", "USD"));
    result.put("series", new ArrayList<>(series.values()));
    result.put("reason", series.isEmpty() ? "NO_HISTORY" : null);
    result.put("asOf", Instant.now());
    result.put("nextCursor", nextCursor);
    return ApiResponse.success(result);
  }

  private List<Map<String, Object>> historyRows(Map<String, Object> query, String offerFilter) {
    return jdbc.queryForList(
        """
        SELECT o.observation_id, o.offer_id, o.check_id, o.collected_at, o.effective_from,
               f.contract_type, f.commitment_months, f.payment_option, p.gpu_count, p.system_type,
               p.service_option, p.country_code, p.region_code, p.provider_id, v.provider_name,
               v.provider_type, g.model_name, n.normalization_id, n.effective_usd_gpu_hour,
               n.result_status, n.reason AS normalization_reason, n.rule_version
        FROM price_observation o JOIN offer f ON f.offer_id = o.offer_id
        JOIN product p ON p.product_id = f.product_id
        JOIN provider v ON v.provider_id = p.provider_id
        JOIN gpu_model g ON g.gpu_model_id = p.gpu_model_id
        LEFT JOIN LATERAL (
          SELECT pn.*, nr.rule_version, count(*) OVER () AS versions
          FROM price_normalization pn JOIN normalization_rule nr ON nr.rule_id = pn.rule_id
          WHERE pn.observation_id = o.observation_id
        ) n ON n.versions = 1
        WHERE g.gpu_model_id = :modelId AND o.collected_at >= :start AND o.collected_at < :end
        %s
        ORDER BY o.offer_id, o.collected_at, o.observation_id LIMIT :pointLimit
        """
            .formatted(offerFilter),
        query);
  }

  private static int historyLimit(String value) {
    try {
      int limit = Integer.parseInt(value);
      if (limit > 0 && limit <= MAX_PAGE_SIZE) {
        return limit;
      }
    } catch (NumberFormatException ignored) {
      // Reject malformed and out-of-range values with the same response.
    }
    throw new InvalidRequestException("가격 이력 limit은 1~100 사이의 정수여야 합니다.");
  }

  private static long historyCursor(String value) {
    if (value == null) {
      return 0;
    }
    try {
      long offerId = Long.parseLong(value);
      if (offerId > 0) {
        return offerId;
      }
    } catch (NumberFormatException ignored) {
      // Reject malformed and out-of-range values with the same response.
    }
    throw new InvalidRequestException("가격 이력 cursor가 올바르지 않습니다.");
  }

  private static String camel(String s) {
    StringBuilder b = new StringBuilder();
    boolean upper = false;
    for (char c : s.toCharArray()) {
      if (c == '_') upper = true;
      else {
        b.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return b.toString();
  }
}
