package com.gpumarket.domain.market.service;

import com.gpumarket.domain.exchange.dto.LatestExchangeRateResponseDto;
import com.gpumarket.domain.exchange.service.ExchangeService;
import com.gpumarket.domain.market.dto.ContractComparisonResponseDto;
import com.gpumarket.domain.market.dto.ContractComparisonResponseDto.ContractOfferDto;
import com.gpumarket.domain.market.dto.ContractComparisonResponseDto.DiscountDto;
import com.gpumarket.domain.market.dto.PublicPriceLookupDto;
import com.gpumarket.domain.market.dto.PublicPriceLookupDto.PublicPriceDto;
import com.gpumarket.domain.market.repository.MarketOfferRepository;
import com.gpumarket.global.api.InvalidRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MarketOfferService {
  private static final String DISCLAIMER = "공개 관측 가격이며 실제 거래가와 다를 수 있습니다.";
  private static final int FRESH_HOURS = 48;
  private static final int PUBLIC_PRICE_BATCH_SIZE = 100;
  private static final int PUBLIC_PRICE_LIMIT = 5;
  private static final List<String> TEXT_FILTERS =
      List.of(
          "generation",
          "countryCode",
          "regionCode",
          "systemType",
          "serviceOption",
          "contractType",
          "contractProductType",
          "intraNodeInterconnect",
          "paymentOption");
  private final MarketOfferRepository repository;
  private final ExchangeService exchange;

  public MarketOfferService(MarketOfferRepository repository, ExchangeService exchange) {
    this.repository = repository;
    this.exchange = exchange;
  }

  public Map<String, Object> list(Map<String, String> input) {
    var filters = filters(input, false);
    int limit = input.containsKey("limit") ? integer(input.get("limit"), "limit", 1, 100) : 50;
    boolean includeStale = Boolean.TRUE.equals(filters.applied().get("includeStale"));
    var cursor = cursor(input.get("cursor"), filters.applied());
    var params = query(filters, cursor.asOf());
    if (!includeStale) {
      params.put("cutoff", utc(cursor.asOf().minus(FRESH_HOURS, ChronoUnit.HOURS)));
    }
    params.put("afterId", cursor.afterId());
    params.put("pageSize", limit + 1);
    var fetched = repository.offers(params);
    var rows = fetched.subList(0, Math.min(limit, fetched.size()));
    var rate = rate(filters.currency());
    var related = related(repository, rows);
    var next =
        fetched.size() > limit
            ? encodeCursor(
                new Cursor(cursor.asOf(), number(rows.get(rows.size() - 1), "offer_id")),
                filters.applied())
            : null;
    return map(
        "appliedFilters",
        filters.applied(),
        "items",
        rows.stream().map(row -> offer(row, cursor.asOf(), related, rate)).toList(),
        "asOf",
        cursor.asOf(),
        "nextCursor",
        next,
        "disclaimer",
        DISCLAIMER);
  }

  public PublicPriceLookupDto publicPrices(Map<String, String> input) {
    Instant asOf = Instant.now();
    var params = query(filters(input, false), asOf);
    params.put("publicPriceCutoff", utc(asOf.minus(FRESH_HOURS, ChronoUnit.HOURS)));
    params.put("pageSize", PUBLIC_PRICE_BATCH_SIZE);
    var items = new ArrayList<PublicPriceDto>();
    while (true) {
      var found = repository.offers(params);
      var eligible = found.stream().filter(row -> latestPriceExclusion(row, asOf) == null).toList();
      var related = related(repository, eligible, asOf);
      for (var row : eligible) {
        var sources =
            related.evidence().getOrDefault(id(row, "observation_check_id"), List.of()).stream()
                .filter(source -> publicSource(string(source.get("sourceUrl"))))
                .map(
                    source -> {
                      Map<String, Object> publicEvidence = new LinkedHashMap<>(source);
                      publicEvidence.put("excerpt", null);
                      return publicEvidence;
                    })
                .toList();
        if (sources.isEmpty()) {
          continue;
        }
        items.add(publicPrice(row, related, sources));
      }
      if (found.size() < PUBLIC_PRICE_BATCH_SIZE) {
        return new PublicPriceLookupDto(
            cheapestFirst(items, PUBLIC_PRICE_LIMIT), asOf, items.size() > PUBLIC_PRICE_LIMIT);
      }
      params.put("afterId", number(found.get(found.size() - 1), "offer_id"));
    }
  }

  /** 공개가격 답변은 오퍼 번호 순서가 아니라 GPU·시간 가격이 낮은 순으로 고른다(가격 없음은 뒤로). */
  static List<PublicPriceDto> cheapestFirst(List<PublicPriceDto> items, int limit) {
    return items.stream()
        .sorted(
            Comparator.comparing(
                (PublicPriceDto item) -> parsePrice(item.priceUsdGpuHour()),
                Comparator.nullsLast(Comparator.naturalOrder())))
        .limit(limit)
        .toList();
  }

  private static BigDecimal parsePrice(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static PublicPriceDto publicPrice(
      Map<String, Object> row, Related related, List<Map<String, Object>> sources) {
    return new PublicPriceDto(
        string(row.get("offer_id")),
        string(row.get("observation_id")),
        string(row.get("normalization_id")),
        string(row.get("product_id")),
        string(row.get("provider_id")),
        string(row.get("provider_name")),
        string(row.get("raw_price_text")),
        related.components().getOrDefault(id(row, "observation_id"), List.of()),
        (Integer) row.get("gpu_count"),
        upper(row.get("country_code")),
        string(row.get("region_code")),
        upper(row.get("system_type")),
        upper(row.get("contract_type")),
        time(row, "collected_at"),
        decimal(effectiveAmount(row)),
        sources);
  }

  private static boolean publicSource(String value) {
    if (value == null) {
      return false;
    }
    try {
      var uri = URI.create(value);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public Map<String, Object> detail(long offerId, Map<String, String> input) {
    if (offerId < 1 || !Set.of("currency").containsAll(input.keySet())) {
      throw invalid("양의 오퍼 ID와 currency만 사용할 수 있습니다.");
    }
    String currency = currency(input);
    Instant now = Instant.now();
    var rows = repository.offers(Map.of("offerId", offerId, "asOf", utc(now)));
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "오퍼가 없습니다.");
    }
    var row = rows.get(0);
    var rate = rate(currency);
    return map(
        "offer",
        offer(row, now, related(repository, rows), rate),
        "specEvidence",
        repository.specEvidence(number(row, "product_id")).stream()
            .map(e -> map("attributeName", e.get("attribute_name"), "evidence", evidence(e)))
            .toList(),
        "normalizationExplanation",
        row.get("normalization_id") == null
            ? null
            : map(
                "formulaDescription",
                row.get("formula_description"),
                "precisionPolicy",
                row.get("precision_policy"),
                "monthlyHours",
                row.get("monthly_hours"),
                "ruleVersion",
                row.get("rule_version"),
                "calculationInputs",
                string(row.get("calculation_inputs"))),
        "exchangeRate",
        rate != null && rate.rate() != null ? rate : null);
  }

  public ContractComparisonResponseDto contracts(long productId, Map<String, String> input) {
    if (productId < 1 || !Set.of("currency").containsAll(input.keySet())) {
      throw invalid("양의 상품 ID와 currency만 사용할 수 있습니다.");
    }

    String currency = currency(input);
    Instant asOf = Instant.now();
    var rows = repository.offers(Map.of("productId", productId, "asOf", utc(asOf)));
    if (rows.isEmpty() && !repository.productExists(productId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없습니다.");
    }

    var exchangeRate = rate(currency);
    var baselines =
        rows.stream()
            .filter(row -> "on_demand".equals(row.get("contract_type")))
            .filter(row -> latestPriceExclusion(row, asOf) == null)
            .filter(row -> effectiveAmount(row).signum() > 0)
            .toList();
    var baseline = baselines.size() == 1 ? baselines.get(0) : null;
    var estimatedObservationIds =
        baseline != null
            ? rows.stream()
                .filter(row -> "commitment".equals(row.get("contract_type")))
                .filter(MarketOfferService::hasKnownCommitmentTerms)
                .filter(row -> "estimate".equals(row.get("price_certainty")))
                .filter(row -> "nonExactOffers".equals(latestPriceExclusion(row, asOf)))
                .map(row -> id(row, "observation_id"))
                .toList()
            : List.<Long>of();
    var components = new HashMap<Long, List<Map<String, Object>>>();
    for (var component : repository.components(estimatedObservationIds)) {
      components
          .computeIfAbsent(number(component, "observation_id"), ignored -> new ArrayList<>())
          .add(component);
    }

    var items = new ArrayList<ContractOfferDto>();
    var discounts = new ArrayList<DiscountDto>();
    for (var row : rows) {
      String offerId = string(row.get("offer_id"));
      String type = upper(row.get("contract_type"));
      var effective = price(row, "effective_usd_gpu_hour", certainty(row), exchangeRate);
      String exclusion = latestPriceExclusion(row, asOf);
      if ("notFoundOffers".equals(exclusion)) {
        effective.put("amount", null);
        effective.put("status", "UNAVAILABLE");
        effective.put("reason", "LATEST_PRICE_NOT_AVAILABLE");
        effective.put("krw", exchangeRate == null ? null : krw(exchangeRate, "amount", null));
      } else if (row.get("observation_id") == null) {
        effective.put("reason", "PRICE_NOT_OBSERVED");
      }

      if ("COMMITMENT".equals(type)) {
        String unavailable = contractDiscountReason(baselines.size(), baseline, row, exclusion);
        if (unavailable == null) {
          var baseAmount = effectiveAmount(baseline);
          var discount =
              baseAmount.subtract(effectiveAmount(row)).divide(baseAmount, 6, RoundingMode.HALF_UP);
          discounts.add(
              new DiscountDto(
                  string(baseline.get("offer_id")), offerId, decimal(discount), "OK", null));
        } else {
          discounts.add(
              new DiscountDto(
                  baseline == null ? null : string(baseline.get("offer_id")),
                  offerId,
                  null,
                  "UNAVAILABLE",
                  unavailable));
        }

        if (baseline != null
            && "nonExactOffers".equals(exclusion)
            && "estimate".equals(row.get("price_certainty"))
            && hasKnownCommitmentTerms(row)
            && sameContractMinimum(baseline, row)) {
          var facts = components.getOrDefault(id(row, "observation_id"), List.of());
          applyDiscountScenario(effective, facts, baseline, exchangeRate);
        }
      }

      items.add(
          new ContractOfferDto(
              offerId,
              type == null ? "UNKNOWN" : type,
              string(row.get("contract_product_type")),
              (Integer) row.get("commitment_months"),
              payment(row),
              string(row.get("observation_id")),
              time(row, "collected_at"),
              freshness(time(row, "confirmed_at"), asOf),
              effective));
    }

    return new ContractComparisonResponseDto(
        Long.toString(productId), asOf, items, discounts, DISCLAIMER);
  }

  private static String contractDiscountReason(
      int baselineCount,
      Map<String, Object> baseline,
      Map<String, Object> commitment,
      String exclusion) {
    if (baselineCount != 1) {
      return baselineCount == 0 ? "BASE_PRICE_MISSING" : "AMBIGUOUS_BASE_PRICE";
    }
    if (exclusion != null) {
      return switch (exclusion) {
        case "staleOffers" -> "COMMITMENT_PRICE_STALE";
        case "notFoundOffers" -> "COMMITMENT_PRICE_NOT_AVAILABLE";
        case "nonExactOffers" -> "COMMITMENT_PRICE_NOT_EXACT";
        default -> "COMMITMENT_PRICE_MISSING";
      };
    }
    if (!sameContractMinimum(baseline, commitment)
        || !hasKnownCommitmentTerms(commitment)
        || !hasText(baseline.get("rule_version"))
        || !baseline.get("rule_version").equals(commitment.get("rule_version"))) {
      return "CONDITIONS_NOT_COMPARABLE";
    }
    return null;
  }

  private static boolean hasKnownCommitmentTerms(Map<String, Object> commitment) {
    return commitment.get("commitment_months") instanceof Number months
        && months.intValue() > 0
        && Set.of("ALL_UPFRONT", "PARTIAL_UPFRONT", "NO_UPFRONT").contains(payment(commitment));
  }

  private static boolean sameContractMinimum(
      Map<String, Object> baseline, Map<String, Object> commitment) {
    return Objects.equals(
            decimalKey(baseline.get("minimum_quantity")),
            decimalKey(commitment.get("minimum_quantity")))
        && Objects.equals(
            upper(baseline.get("minimum_quantity_unit")),
            upper(commitment.get("minimum_quantity_unit")))
        && (commitment.get("minimum_quantity") == null
            || hasText(commitment.get("minimum_quantity_unit")));
  }

  private static void applyDiscountScenario(
      Map<String, Object> effective,
      List<Map<String, Object>> components,
      Map<String, Object> baseline,
      LatestExchangeRateResponseDto exchangeRate) {
    if (components.size() != 1) {
      return;
    }
    var component = components.get(0);
    if (!"discount_rate".equals(component.get("component_type"))
        || !(component.get("discount_rate") instanceof BigDecimal discount)
        || discount.signum() < 0
        || discount.compareTo(BigDecimal.ONE) > 0) {
      return;
    }

    var amount =
        effectiveAmount(baseline)
            .multiply(BigDecimal.ONE.subtract(discount))
            .setScale(6, RoundingMode.HALF_UP);
    effective.put("amount", decimal(amount));
    effective.put("status", "OK");
    effective.put("reason", null);
    effective.put("normalizationId", null);
    effective.put("ruleVersion", "contract-discount-scenario-v1");
    effective.put("baselineObservationId", string(baseline.get("observation_id")));
    effective.put("baselineNormalizationId", string(baseline.get("normalization_id")));
    effective.put("assumptions", List.of("동일 상품의 온디맨드 실효 단가에 공개 할인율을 적용한 시나리오"));
    effective.put("krw", exchangeRate == null ? null : krw(exchangeRate, "amount", amount));
  }

  private static BigDecimal effectiveAmount(Map<String, Object> row) {
    return (BigDecimal) row.get("effective_usd_gpu_hour");
  }

  public Map<String, Object> summary(Map<String, String> input) {
    var filters = filters(input, true);
    Instant asOf = Instant.now();
    var groups = new LinkedHashMap<Map<String, Object>, Group>();
    var excluded =
        new LinkedHashMap<>(
            Map.of(
                "staleOffers",
                0,
                "notFoundOffers",
                0,
                "nonExactOffers",
                0,
                "incomparableOffers",
                0,
                "missingPriceOffers",
                0,
                "unavailableNormalizationOffers",
                0));

    for (var row : repository.offers(query(filters, asOf))) {
      var decision = assess(row, asOf);
      if (decision.reason() != null) {
        excluded.merge(decision.reason(), 1, Integer::sum);
        continue;
      }
      groups.computeIfAbsent(decision.key(), key -> new Group()).add(row);
    }

    var rate = rate(filters.currency());
    return map(
        "appliedFilters",
        filters.applied(),
        "groups",
        groups.entrySet().stream().map(e -> e.getValue().response(e.getKey(), asOf, rate)).toList(),
        "excludedSummary",
        excluded,
        "asOf",
        asOf,
        "rules",
        map(
            "comparisonVersion",
            "same-region-config-v1",
            "aggregationVersion",
            "provider-lowest-v1",
            "freshnessHours",
            FRESH_HOURS),
        "disclaimer",
        DISCLAIMER);
  }

  public List<ComparableGroup> comparablePrices(
      Map<String, String> input, boolean requirePositive) {
    Instant asOf = Instant.now();
    var eligible =
        repository.offers(query(filters(input, true), asOf)).stream()
            .filter(row -> assess(row, asOf).reason() == null)
            .filter(row -> !requirePositive || effectiveAmount(row).signum() > 0)
            .toList();
    var checkIds =
        eligible.stream().map(row -> id(row, "observation_check_id")).distinct().toList();
    var byCheck = new HashMap<Long, List<Map<String, Object>>>();
    for (var source : repository.comparablePriceEvidence(checkIds, utc(asOf))) {
      var item = evidence(source);
      if (!isComparablePublicLink(string(item.get("sourceUrl")))) continue;
      item.put("excerpt", null);
      byCheck.computeIfAbsent(id(source, "check_id"), ignored -> new ArrayList<>()).add(item);
    }

    var rowsByGroup = new LinkedHashMap<Map<String, Object>, List<Map<String, Object>>>();
    for (var row : eligible) {
      if (!byCheck.containsKey(id(row, "observation_check_id"))) continue;
      rowsByGroup.computeIfAbsent(groupKey(row), ignored -> new ArrayList<>()).add(row);
    }
    return rowsByGroup.entrySet().stream()
        .map(
            entry -> {
              var rows = entry.getValue();
              var counts =
                  Map.of(
                      "providerCount",
                          (int) rows.stream().map(row -> id(row, "provider_id")).distinct().count(),
                      "productCount",
                          (int) rows.stream().map(row -> id(row, "product_id")).distinct().count(),
                      "offerCount", rows.size(),
                      "observationCount",
                          (int)
                              rows.stream()
                                  .map(row -> id(row, "observation_id"))
                                  .distinct()
                                  .count());
              var offers =
                  rows.stream()
                      .map(
                          row ->
                              new ComparableOffer(
                                  string(row.get("offer_id")),
                                  id(row, "provider_id"),
                                  effectiveAmount(row),
                                  price(row, "effective_usd_gpu_hour", certainty(row), null),
                                  time(row, "collected_at"),
                                  byCheck.get(id(row, "observation_check_id"))))
                      .toList();
              return new ComparableGroup(entry.getKey(), counts, offers, asOf);
            })
        .toList();
  }

  private static boolean isComparablePublicLink(String sourceUrl) {
    if (sourceUrl == null) return false;
    try {
      var uri = URI.create(sourceUrl);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  public record ComparableGroup(
      Map<String, Object> key,
      Map<String, Integer> sampleCounts,
      List<ComparableOffer> offers,
      Instant asOf) {}

  public record ComparableOffer(
      String offerId,
      long providerId,
      BigDecimal amount,
      Map<String, Object> price,
      Instant collectedAt,
      List<Map<String, Object>> evidence) {}

  private static Decision assess(Map<String, Object> row, Instant asOf) {
    var key = groupKey(row);
    String exclusion = latestPriceExclusion(row, asOf);
    return new Decision(key, exclusion == null && key == null ? "incomparableOffers" : exclusion);
  }

  private static String latestPriceExclusion(Map<String, Object> row, Instant asOf) {
    Instant confirmedAt = time(row, "confirmed_at");
    Instant checkedAt = time(row, "disclosure_checked_at");
    boolean latest = checkedAt != null && (confirmedAt == null || !checkedAt.isBefore(confirmedAt));
    if (latest
        && ("not_found".equals(row.get("disclosure_processing_status"))
            || Set.of("not_disclosed", "login_required", "contact_sales")
                .contains(stringOrEmpty(row.get("disclosure_status"))))) {
      return "notFoundOffers";
    }
    if (row.get("observation_id") == null) {
      return "missingPriceOffers";
    }
    if (confirmedAt.isBefore(asOf.minus(FRESH_HOURS, ChronoUnit.HOURS))) {
      return "staleOffers";
    }
    if (!"firm".equals(row.get("price_certainty"))) {
      return "nonExactOffers";
    }
    if (!"success".equals(row.get("result_status")) || row.get("effective_usd_gpu_hour") == null) {
      return "unavailableNormalizationOffers";
    }
    return null;
  }

  private static Map<String, Object> groupKey(Map<String, Object> row) {
    for (String field :
        List.of(
            "provider_type",
            "country_code",
            "region_code",
            "system_type",
            "service_option",
            "gpu_count",
            "contract_type",
            "contract_product_type",
            "intra_node_interconnect")) {
      String value = stringOrEmpty(row.get(field));
      if (value.isBlank()
          || Set.of("unknown", "unconfirmed").contains(value.toLowerCase(Locale.ROOT))) {
        return null;
      }
    }
    boolean general = "GENERAL_CLOUD".equals(row.get("provider_type"));
    boolean commitment = "commitment".equals(row.get("contract_type"));
    if (!"confirmed".equals(row.get("interconnect_status"))
        || (general && (row.get("vcpu_count") == null || row.get("ram_gb") == null))
        || (commitment
            && (row.get("commitment_months") == null || row.get("payment_option") == null))
        || (row.get("minimum_quantity") != null && !hasText(row.get("minimum_quantity_unit")))) {
      return null;
    }
    return map(
        "modelId",
        string(row.get("gpu_model_id")),
        "providerType",
        row.get("provider_type"),
        "countryCode",
        upper(row.get("country_code")),
        "regionCode",
        row.get("region_code"),
        "systemType",
        upper(row.get("system_type")),
        "serviceOption",
        upper(row.get("service_option")),
        "gpuCount",
        row.get("gpu_count"),
        "contractType",
        upper(row.get("contract_type")),
        "contractProductType",
        upper(row.get("contract_product_type")),
        "commitmentMonths",
        row.get("commitment_months"),
        "paymentOption",
        payment(row),
        "minimumQuantity",
        decimalKey(row.get("minimum_quantity")),
        "minimumQuantityUnit",
        upper(row.get("minimum_quantity_unit")),
        "intraNodeInterconnect",
        row.get("intra_node_interconnect"),
        "vcpuCount",
        decimalKey(row.get("vcpu_count")),
        "ramGb",
        decimalKey(row.get("ram_gb")),
        "vramGbPerGpu",
        decimalKey(row.get("vram_gb_per_gpu")),
        "specScope",
        row.get("spec_scope"),
        "storageDescription",
        row.get("storage_description"),
        "interNodeNetwork",
        row.get("inter_node_network"));
  }

  private Map<String, Object> offer(
      Map<String, Object> row, Instant asOf, Related related, LatestExchangeRateResponseDto rate) {
    var decision = assess(row, asOf);
    Instant confirmedAt = time(row, "confirmed_at");

    return map(
        "offerId",
        string(row.get("offer_id")),
        "productId",
        string(row.get("product_id")),
        "providerId",
        string(row.get("provider_id")),
        "providerName",
        row.get("provider_name"),
        "providerType",
        row.get("provider_type"),
        "modelId",
        string(row.get("gpu_model_id")),
        "modelName",
        row.get("model_name"),
        "product",
        map(
            "sku",
            row.get("sku"),
            "originalProductName",
            row.get("original_product_name"),
            "originalModelName",
            row.get("original_model_name"),
            "systemType",
            upper(row.get("system_type")),
            "serviceOption",
            upper(row.get("service_option")),
            "gpuCount",
            row.get("gpu_count"),
            "vcpuCount",
            row.get("vcpu_count"),
            "ramGb",
            row.get("ram_gb"),
            "vramGbPerGpu",
            row.get("vram_gb_per_gpu"),
            "specScope",
            row.get("spec_scope"),
            "storageDescription",
            row.get("storage_description"),
            "intraNodeInterconnect",
            row.get("intra_node_interconnect"),
            "interconnectStatus",
            upper(row.get("interconnect_status")),
            "interNodeNetwork",
            row.get("inter_node_network"),
            "configurationVersion",
            row.get("configuration_version"),
            "countryCode",
            row.get("country_code"),
            "regionCode",
            row.get("region_code")),
        "contract",
        map(
            "type",
            upper(row.get("contract_type")),
            "productType",
            row.get("contract_product_type"),
            "months",
            row.get("commitment_months"),
            "paymentOption",
            payment(row),
            "minimumQuantity",
            string(row.get("minimum_quantity")),
            "minimumQuantityUnit",
            row.get("minimum_quantity_unit")),
        "lastSuccessfulObservation",
        observation(row, related, rate),
        "latestDisclosure",
        row.get("disclosure_check_id") == null
            ? null
            : map(
                "status",
                upper(row.get("disclosure_status")),
                "verifiedAt",
                time(row, "disclosure_checked_at"),
                "evidence",
                related.evidence().getOrDefault(id(row, "disclosure_check_id"), List.of())),
        "latestCheck",
        row.get("check_id") == null
            ? null
            : map(
                "processingStatus",
                upper(row.get("processing_status")),
                "checkedAt",
                time(row, "checked_at"),
                "reason",
                row.get("reason")),
        "freshness",
        map(
            "status",
            freshness(confirmedAt, asOf),
            "lastSuccessAt",
            confirmedAt,
            "evaluatedAt",
            asOf,
            "ruleVersion",
            "48h-v1"),
        "comparison",
        map(
            "type",
            "SAME_CONDITION",
            "groupKey",
            decision.key(),
            "eligibleForAggregate",
            decision.reason() == null,
            "referenceOfferId",
            null,
            "differences",
            List.of(),
            "excludedReasons",
            decision.reason() == null ? List.of() : List.of(decision.reason())));
  }

  static Map<String, Object> observation(
      Map<String, Object> row, Related related, LatestExchangeRateResponseDto rate) {
    String certainty = certainty(row);
    return row.get("observation_id") == null
        ? null
        : map(
            "observationId",
            string(row.get("observation_id")),
            "collectedAt",
            time(row, "collected_at"),
            "effectiveFrom",
            time(row, "effective_from"),
            "priceCertainty",
            certainty,
            "rawPriceText",
            row.get("raw_price_text"),
            "components",
            related.components().getOrDefault(id(row, "observation_id"), List.of()),
            "nominal",
            price(row, "nominal_usd_gpu_hour", certainty, rate),
            "effective",
            price(row, "effective_usd_gpu_hour", certainty, rate),
            "evidence",
            related.evidence().getOrDefault(id(row, "observation_check_id"), List.of()));
  }

  static Related related(MarketOfferRepository repository, List<Map<String, Object>> rows) {
    return related(repository, rows, null);
  }

  private static Related related(
      MarketOfferRepository repository, List<Map<String, Object>> rows, Instant publicPriceAsOf) {
    var checks = new HashSet<Long>();
    var observations = new HashSet<Long>();
    for (var row : rows) {
      checks.add(id(row, "check_id"));
      checks.add(id(row, "disclosure_check_id"));
      checks.add(id(row, "observation_check_id"));
      observations.add(id(row, "observation_id"));
    }
    checks.remove(null);
    observations.remove(null);
    var evidence = new HashMap<Long, List<Map<String, Object>>>();
    var checkIds = List.copyOf(checks);
    var evidenceRows =
        publicPriceAsOf == null
            ? repository.evidence(checkIds)
            : repository.publicPriceEvidence(checkIds, utc(publicPriceAsOf));
    for (var row : evidenceRows) {
      evidence
          .computeIfAbsent(id(row, "check_id"), ignored -> new ArrayList<>())
          .add(evidence(row));
    }
    var components = new HashMap<Long, List<Map<String, Object>>>();
    for (var row : repository.components(List.copyOf(observations))) {
      components
          .computeIfAbsent(id(row, "observation_id"), ignored -> new ArrayList<>())
          .add(
              map(
                  "componentId",
                  string(row.get("component_id")),
                  "type",
                  upper(row.get("component_type")),
                  "amount",
                  string(row.get("amount")),
                  "currency",
                  row.get("currency"),
                  "timeUnit",
                  upper(row.get("time_unit")),
                  "appliesTo",
                  upper(row.get("applies_to")),
                  "discountRate",
                  string(row.get("discount_rate")),
                  "rawValue",
                  row.get("raw_value")));
    }
    return new Related(evidence, components);
  }

  static Map<String, Object> evidence(Map<String, Object> row) {
    return map(
        "evidenceId",
        string(row.get("evidence_id")),
        "sourceId",
        string(row.get("source_id")),
        "role",
        "official".equals(row.get("evidence_role")) ? "PRIMARY" : upper(row.get("evidence_role")),
        "sourceName",
        row.get("source_name"),
        "sourceUrl",
        row.get("source_url"),
        "sourceVersion",
        row.get("source_version"),
        "excerpt",
        row.get("excerpt"),
        "capturedAt",
        time(row, "captured_at"),
        "primaryVerified",
        row.get("primary_verified"),
        "verifiedAt",
        time(row, "verified_at"),
        "collectionMethod",
        null,
        "collectionMethodUnavailableReason",
        "수집 당시 방식이 근거에 기록되지 않았습니다.");
  }

  static String freshness(Instant confirmedAt, Instant asOf) {
    if (confirmedAt == null) {
      return "UNKNOWN";
    }
    return confirmedAt.isBefore(asOf.minus(FRESH_HOURS, ChronoUnit.HOURS)) ? "STALE" : "CURRENT";
  }

  private static String certainty(Map<String, Object> row) {
    return switch (stringOrEmpty(row.get("price_certainty"))) {
      case "firm" -> "EXACT";
      case "starting_from" -> "FROM";
      case "range" -> "RANGE";
      case "estimate" -> "SCENARIO";
      default -> "UNKNOWN";
    };
  }

  private static Map<String, Object> price(
      Map<String, Object> row, String field, String certainty, LatestExchangeRateResponseDto rate) {
    var value = "success".equals(row.get("result_status")) ? (BigDecimal) row.get(field) : null;
    String reason =
        value != null
            ? null
            : (row.get("normalization_reason") != null
                ? string(row.get("normalization_reason"))
                : "사용할 수 있는 단일 환산 결과가 없습니다.");
    return map(
        "normalizationId",
        string(row.get("normalization_id")),
        "amount",
        decimal(value),
        "kind",
        certainty,
        "status",
        value != null ? "OK" : "UNAVAILABLE",
        "reason",
        reason,
        "monthlyHours",
        string(row.get("monthly_hours")),
        "ruleVersion",
        row.get("rule_version"),
        "assumptions",
        List.of(),
        "baselineObservationId",
        null,
        "baselineNormalizationId",
        null,
        "krw",
        rate == null ? null : krw(rate, "amount", value));
  }

  private static Map<String, Object> krw(LatestExchangeRateResponseDto rate, Object... amounts) {
    var factor = rate.rate() == null ? null : new BigDecimal(rate.rate());
    boolean available = factor != null && amounts[1] != null;
    var result =
        map(
            "status",
            available ? "OK" : "UNAVAILABLE",
            "reason",
            available ? null : factor == null ? rate.reason() : "환산할 USD 가격이 없습니다.",
            "unit",
            "KRW/GPU-hour",
            "exchangeRateId",
            rate.exchangeRateId(),
            "krwPerUsd",
            rate.rate(),
            "referenceDate",
            rate.referenceDate(),
            "source",
            rate.source(),
            "selectionPolicyVersion",
            rate.selectionPolicyVersion(),
            "isPreviousBusinessDay",
            rate.isPreviousBusinessDay());
    for (int i = 0; i < amounts.length; i += 2) {
      result.put(
          (String) amounts[i],
          available
              ? ((BigDecimal) amounts[i + 1])
                  .multiply(factor)
                  .setScale(6, RoundingMode.HALF_UP)
                  .toPlainString()
              : null);
    }
    return result;
  }

  private LatestExchangeRateResponseDto rate(String currency) {
    return "KRW".equals(currency) ? exchange.latest() : null;
  }

  private static Filters filters(Map<String, String> input, boolean summary) {
    var allowed = new HashSet<>(TEXT_FILTERS);
    allowed.addAll(
        Set.of(
            "modelId",
            "providerIds",
            "providerType",
            "gpuCount",
            "commitmentMonths",
            "currency",
            "comparisonType"));
    if (!summary) {
      allowed.addAll(Set.of("includeStale", "limit", "cursor"));
    }
    if (!allowed.containsAll(input.keySet())) {
      throw invalid("지원하지 않는 가격 조회 조건이 있습니다.");
    }
    if (summary && !input.containsKey("modelId")) {
      throw invalid("시장 요약에는 modelId가 필요합니다.");
    }
    if (!"SAME_CONDITION".equals(input.getOrDefault("comparisonType", "SAME_CONDITION"))) {
      throw invalid("현재 동일 리전의 SAME_CONDITION 비교만 지원합니다.");
    }
    var sql = new HashMap<String, Object>();
    var applied = new TreeMap<String, Object>();
    for (String key : TEXT_FILTERS) {
      if (input.containsKey(key)) {
        var value = input.get(key).strip();
        if (value.isEmpty() || value.length() > 200) {
          throw invalid(key + " 값이 유효하지 않습니다.");
        }
        applied.put(key, value);
        sql.put(key, value);
      }
    }
    if (input.containsKey("modelId")) {
      long modelId = positive(input.get("modelId"), "modelId");
      sql.put("modelId", modelId);
      applied.put("modelId", Long.toString(modelId));
    }
    if (input.containsKey("providerIds")) {
      var raw = input.get("providerIds").split(",", -1);
      if (raw.length > 100) {
        throw invalid("사업자 조회는 최대 100개까지 지원합니다.");
      }
      var ids =
          Arrays.stream(raw)
              .map(v -> positive(v.strip(), "providerIds"))
              .distinct()
              .sorted()
              .toList();
      sql.put("providerIds", ids);
      applied.put("providerIds", ids.stream().map(String::valueOf).toList());
    }
    for (String key : List.of("gpuCount", "commitmentMonths")) {
      if (input.containsKey(key)) {
        int value = integer(input.get(key), key, 1, Integer.MAX_VALUE);
        sql.put(key, value);
        applied.put(key, value);
      }
    }
    String provider = input.getOrDefault("providerType", "ALL");
    switch (provider) {
      case "GENERAL_CLOUD" -> sql.put("providerType", List.of("hyperscaler"));
      case "GPU_SPECIALIST" -> sql.put("providerType", List.of("gpu_specialist"));
      case "UNKNOWN" -> sql.put("providerType", List.of("", "unknown"));
      case "ALL" -> {}
      default -> throw invalid("지원하지 않는 providerType입니다.");
    }
    applied.put("providerType", provider);
    applied.put("comparisonType", "SAME_CONDITION");
    var currency = currency(input);
    applied.put("currency", currency);
    if (!summary) {
      String stale = input.getOrDefault("includeStale", "false");
      if (!Set.of("true", "false").contains(stale)) {
        throw invalid("includeStale은 true 또는 false여야 합니다.");
      }
      applied.put("includeStale", Boolean.parseBoolean(stale));
    }
    return new Filters(applied, sql, currency);
  }

  private static Map<String, Object> query(Filters filters, Instant asOf) {
    var params = new HashMap<>(filters.sql());
    params.put("asOf", utc(asOf));
    return params;
  }

  private static String currency(Map<String, String> input) {
    var currency = input.getOrDefault("currency", "USD");
    if (!Set.of("USD", "KRW").contains(currency)) {
      throw invalid("USD 또는 KRW만 지원합니다.");
    }
    return currency;
  }

  private static Cursor cursor(String token, Map<String, Object> filters) {
    if (token == null) {
      return new Cursor(Instant.now(), 0);
    }
    try {
      if (token.length() > 256) {
        throw new IllegalArgumentException();
      }
      var parts =
          new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
      if (parts.length != 3 || !parts[2].equals(Integer.toString(filters.hashCode()))) {
        throw new IllegalArgumentException();
      }
      Instant asOf = Instant.parse(parts[0]);
      long afterId = Long.parseLong(parts[1]);
      if (asOf.isAfter(Instant.now()) || afterId < 1) {
        throw new IllegalArgumentException();
      }
      return new Cursor(asOf, afterId);
    } catch (IllegalArgumentException | java.time.DateTimeException ex) {
      throw invalid("커서가 유효하지 않거나 현재 조회 조건과 다릅니다.");
    }
  }

  private static String encodeCursor(Cursor cursor, Map<String, Object> filters) {
    var value = cursor.asOf() + "|" + cursor.afterId() + "|" + filters.hashCode();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static long positive(String value, String field) {
    try {
      long id = Long.parseLong(value);
      if (id > 0) {
        return id;
      }
    } catch (NumberFormatException ignored) {
      // Report the same validation error for malformed and out-of-range values.
    }
    throw invalid(field + "에는 양의 정수가 필요합니다.");
  }

  private static int integer(String value, String field, int min, int max) {
    try {
      int number = Integer.parseInt(value);
      if (number >= min && number <= max) {
        return number;
      }
    } catch (NumberFormatException ignored) {
      // Report the supported range below.
    }
    throw invalid(field + " 범위는 " + min + "~" + max + "입니다.");
  }

  private static InvalidRequestException invalid(String message) {
    return new InvalidRequestException(message);
  }

  private static String payment(Map<String, Object> row) {
    if (row.get("payment_option") != null) {
      return upper(row.get("payment_option"));
    }
    return List.of("on_demand", "spot").contains(stringOrEmpty(row.get("contract_type")))
        ? "NOT_APPLICABLE"
        : "UNKNOWN";
  }

  private static Instant time(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return value instanceof OffsetDateTime offset ? offset.toInstant() : (Instant) value;
  }

  private static OffsetDateTime utc(Instant time) {
    return time.atOffset(ZoneOffset.UTC);
  }

  private static Long id(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : number(row, key);
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static boolean hasText(Object value) {
    return !stringOrEmpty(value).isBlank() && !"UNKNOWN".equals(upper(value));
  }

  private static String upper(Object value) {
    return value == null ? null : value.toString().toUpperCase(Locale.ROOT);
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static String stringOrEmpty(Object value) {
    return value == null ? "" : value.toString();
  }

  private static String decimal(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private static Object decimalKey(Object value) {
    return value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : value;
  }

  private static Map<String, Object> map(Object... fields) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < fields.length; i += 2) {
      result.put((String) fields[i], fields[i + 1]);
    }
    return result;
  }

  private record Filters(Map<String, Object> applied, Map<String, Object> sql, String currency) {}

  private record Cursor(Instant asOf, long afterId) {}

  private record Decision(Map<String, Object> key, String reason) {}

  record Related(
      Map<Long, List<Map<String, Object>>> evidence,
      Map<Long, List<Map<String, Object>>> components) {}

  public record PriceSummary(BigDecimal minimum, BigDecimal median, BigDecimal maximum) {
    public static PriceSummary fromRepresentatives(Collection<BigDecimal> prices) {
      var values = prices.stream().sorted().toList();
      int middle = values.size() / 2;
      var median = values.get(middle);
      if (values.size() % 2 == 0) {
        median = median.add(values.get(middle - 1)).divide(BigDecimal.valueOf(2));
      }
      return new PriceSummary(values.get(0), median, values.get(values.size() - 1));
    }
  }

  private static class Group {
    private final Map<Long, BigDecimal> providers = new HashMap<>();
    private final Set<Long> products = new HashSet<>();
    private int offers;

    void add(Map<String, Object> row) {
      providers.merge(
          number(row, "provider_id"),
          (BigDecimal) row.get("effective_usd_gpu_hour"),
          BigDecimal::min);
      products.add(number(row, "product_id"));
      offers++;
    }

    Map<String, Object> response(
        Map<String, Object> key, Instant asOf, LatestExchangeRateResponseDto rate) {
      int size = providers.size();
      var summary = PriceSummary.fromRepresentatives(providers.values());
      var min = summary.minimum();
      var median = summary.median();
      var max = summary.maximum();
      return map(
          "groupKey",
          key,
          "metric",
          "EFFECTIVE_USD_PER_GPU_HOUR",
          "aggregationUnit",
          "PROVIDER_REPRESENTATIVE_LOWEST",
          "minimum",
          decimal(min),
          "median",
          decimal(median),
          "maximum",
          decimal(max),
          "sampleCounts",
          map(
              "providerCount",
              size,
              "productCount",
              products.size(),
              "offerCount",
              offers,
              "observationCount",
              offers),
          "warnings",
          size == 1 ? List.of("SINGLE_PROVIDER") : List.of(),
          "krwSummary",
          rate == null ? null : krw(rate, "minimum", min, "median", median, "maximum", max),
          "asOf",
          asOf);
    }
  }
}
