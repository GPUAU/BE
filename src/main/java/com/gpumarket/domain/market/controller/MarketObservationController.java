package com.gpumarket.domain.market.controller;

import com.gpumarket.domain.market.repository.MarketRepository;
import com.gpumarket.global.api.ApiResponse;
import com.gpumarket.global.api.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MarketObservationController {
  private final MarketRepository repository;

  public MarketObservationController(MarketRepository repository) {
    this.repository = repository;
  }

  @Operation(
      summary = "가격 관측과 출처 조회",
      description = "이력의 관측 ID와 환산 ID로 당시 원문, 수집 시각과 근거를 확인합니다. 현재 USD만 지원합니다.")
  @GetMapping("/api/v1/market/observations/{observationId}")
  public ApiResponse<Map<String, Object>> observation(
      @Parameter(description = "가격 관측 ID", example = "10421") @PathVariable long observationId,
      @Parameter(description = "해당 관측의 환산 ID", example = "10421") @RequestParam(required = false)
          Long normalizationId,
      @RequestParam(defaultValue = "USD") String currency) {
    if (!currency.equals("USD")) throw new InvalidRequestException("현재 USD만 지원합니다.");
    var rows = repository.observation(observationId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "관측이 없습니다.");
    var row = rows.get(0);
    var normalizations = repository.normalizations(observationId);
    Map<String, Object> selected = null;
    if (normalizationId != null) {
      selected =
          normalizations.stream()
              .filter(n -> ((Number) n.get("normalization_id")).longValue() == normalizationId)
              .findFirst()
              .orElse(null);
      if (selected == null) throw new InvalidRequestException("환산 결과가 관측에 속하지 않거나 존재하지 않습니다.");
    }
    String kind =
        switch ((String) row.get("price_certainty")) {
          case "firm" -> "EXACT";
          case "starting_from" -> "FROM";
          case "range" -> "RANGE";
          case "estimate" -> "SCENARIO";
          default -> "UNKNOWN";
        };
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("observationId", Long.toString(observationId));
    result.put("offerId", row.get("offer_id").toString());
    result.put("productId", row.get("product_id").toString());
    result.put("collectedAt", row.get("collected_at"));
    result.put("effectiveFrom", row.get("effective_from"));
    result.put("priceCertainty", kind);
    result.put("rawPriceText", row.get("raw_price_text"));
    result.put(
        "components",
        repository.components(observationId).stream()
            .map(
                c ->
                    map(
                        "componentId",
                        id(c.get("component_id")),
                        "type",
                        upper(c.get("component_type")),
                        "amount",
                        str(c.get("amount")),
                        "currency",
                        c.get("currency"),
                        "timeUnit",
                        upper(c.get("time_unit")),
                        "appliesTo",
                        upper(c.get("applies_to")),
                        "discountRate",
                        str(c.get("discount_rate")),
                        "rawValue",
                        c.get("raw_value")))
            .toList());
    result.put("nominal", price(selected, "nominal_usd_gpu_hour", kind));
    result.put("effective", price(selected, "effective_usd_gpu_hour", kind));
    result.put(
        "evidence",
        repository.evidence(((Number) row.get("check_id")).longValue()).stream()
            .map(
                e ->
                    map(
                        "evidenceId",
                        id(e.get("evidence_id")),
                        "sourceId",
                        id(e.get("source_id")),
                        "role",
                        "official".equals(e.get("evidence_role"))
                            ? "PRIMARY"
                            : upper(e.get("evidence_role")),
                        "sourceName",
                        e.get("source_name"),
                        "sourceUrl",
                        e.get("source_url"),
                        "collectionMethod",
                        null,
                        "collectionMethodUnavailableReason",
                        "수집 당시 방식이 근거에 기록되지 않았습니다.",
                        "sourceVersion",
                        e.get("source_version"),
                        "excerpt",
                        null,
                        "capturedAt",
                        e.get("captured_at"),
                        "primaryVerified",
                        e.get("primary_verified"),
                        "verifiedAt",
                        e.get("verified_at")))
            .toList());
    result.put(
        "correction",
        row.get("corrects_observation_id") == null
            ? null
            : map(
                "correctsObservationId",
                id(row.get("corrects_observation_id")),
                "reason",
                row.get("correction_reason")));
    result.put(
        "normalizationSelection",
        selected == null
            ? null
            : map(
                "normalizationId",
                id(selected.get("normalization_id")),
                "ruleVersion",
                selected.get("rule_version"),
                "selectionType",
                "REQUESTED"));
    result.put(
        "normalizationExplanation",
        selected == null
            ? null
            : map(
                "formulaDescription",
                selected.get("formula_description"),
                "monthlyHours",
                str(selected.get("monthly_hours")),
                "precisionPolicy",
                selected.get("precision_policy"),
                "calculationInputs",
                selected.get("calculation_inputs").toString()));
    return ApiResponse.success(result);
  }

  private Map<String, Object> price(Map<String, Object> n, String field, String kind) {
    Object amount = n == null ? null : n.get(field);
    boolean ok = n != null && "success".equals(n.get("result_status")) && amount != null;
    return map(
        "normalizationId",
        n == null ? null : id(n.get("normalization_id")),
        "amount",
        ok ? str(amount) : null,
        "status",
        ok ? "OK" : "UNAVAILABLE",
        "reason",
        n == null
            ? "활성 환산 규칙이 지정되지 않았습니다. normalizationId를 지정하세요."
            : (ok ? null : (n.get("reason") == null ? "환산값이 없습니다." : n.get("reason"))),
        "kind",
        kind,
        "ruleVersion",
        n == null ? null : n.get("rule_version"),
        "monthlyHours",
        n == null ? null : str(n.get("monthly_hours")),
        "assumptions",
        List.of(),
        "baselineObservationId",
        null,
        "baselineNormalizationId",
        null,
        "krw",
        null);
  }

  private static String id(Object v) {
    return v == null ? null : v.toString();
  }

  private static String str(Object v) {
    return v == null ? null : v.toString();
  }

  private static String upper(Object v) {
    return v == null ? null : v.toString().toUpperCase(Locale.ROOT);
  }

  private static Map<String, Object> map(Object... values) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < values.length; i += 2) m.put((String) values[i], values[i + 1]);
    return m;
  }
}
