package com.gpumarket.domain.simulation.service;

import com.gpumarket.domain.catalog.repository.CatalogRepository;
import com.gpumarket.domain.market.service.MarketOfferService;
import com.gpumarket.domain.market.service.MarketOfferService.ComparableGroup;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.CostItem;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.Currency;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.InterestRateType;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ItemStatus;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ItemType;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.MarketFilters;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.Period;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.PricingMethod;
import com.gpumarket.domain.simulation.dto.CostSimulationResponseDto;
import com.gpumarket.domain.simulation.dto.CostSimulationResponseDto.AppliedAssumptions;
import com.gpumarket.domain.simulation.dto.CostSimulationResponseDto.MarketComparison;
import com.gpumarket.domain.simulation.dto.CostSimulationResponseDto.MarketGroup;
import com.gpumarket.domain.simulation.dto.CostSimulationResponseDto.MarketOfferResult;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CostSimulationService {
  private static final MathContext PRECISION = MathContext.DECIMAL128;
  private static final BigDecimal YEAR_HOURS = BigDecimal.valueOf(8760);
  private static final BigDecimal ONE = BigDecimal.ONE;
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final List<ItemType> REVIEW_ITEMS =
      List.of(
          ItemType.ACQUISITION,
          ItemType.NETWORK_STORAGE,
          ItemType.RACK_FACILITY,
          ItemType.RENT,
          ItemType.OPERATIONS,
          ItemType.FINANCING);
  private static final Set<ItemType> CAPITAL_ITEMS =
      Set.of(
          ItemType.ACQUISITION,
          ItemType.GPU,
          ItemType.MEMORY,
          ItemType.NETWORK_STORAGE,
          ItemType.RACK_FACILITY,
          ItemType.OTHER);

  private final CatalogRepository catalog;
  private final MarketOfferService market;

  public CostSimulationService(CatalogRepository catalog, MarketOfferService market) {
    this.catalog = catalog;
    this.market = market;
  }

  public CostSimulationResponseDto simulate(CostSimulationRequestDto request) {
    var assumptions = request.assumptions();
    var pricing = request.pricing();
    boolean scenario = "SCREEN_SCENARIO".equals(assumptions.costProfile());
    if (!scenario
        && (assumptions.calendarMonth() != null
            || assumptions.residualValueUsd() != null
            || request.electricity() != null)) {
      throw invalid("assumptions.costProfile", "월별·잔존가치·전기료 가정은 SCREEN_SCENARIO에서 사용하세요.");
    }
    var target = pricing.targetRate();
    if (pricing.method() == PricingMethod.REVENUE_MARGIN && target.compareTo(ONE) >= 0) {
      throw invalid("pricing.targetRate", "매출 기준 이익률은 100% 미만이어야 합니다.");
    }

    var costs = itemizedCosts(request.items(), request.inputFxKrwPerUsd(), scenario);
    var acquisition = costs.acquisition();
    var operating = costs.operating();
    var hasCapital = costs.hasCapital();
    var residual = assumptions.residualValueUsd() == null ? ZERO : assumptions.residualValueUsd();
    if (residual.compareTo(acquisition) > 0) {
      throw invalid("assumptions.residualValueUsd", "잔존가치는 초기 투자금 이하여야 합니다.");
    }
    int periodHours = YEAR_HOURS.intValueExact();
    if (assumptions.calendarMonth() != null) {
      var month = YearMonth.parse(assumptions.calendarMonth());
      if (month.getYear() < 1900) {
        throw invalid("assumptions.calendarMonth", "적용 연도는 1900년 이상이어야 합니다.");
      }
      periodHours = month.lengthOfMonth() * 24;
    }
    BigDecimal electricityHourly = electricityHourly(request, costs.items());
    if (!costs.hasCost()) {
      throw invalid("items", "금액을 반영한 원가 항목이 하나 이상 필요합니다.");
    }

    var loanRate = assumptions.annualInterestRate();
    var rateType = assumptions.interestRateType();
    validateFinancing(costs.items().get(ItemType.FINANCING), loanRate, rateType);

    var warnings = new ArrayList<String>();
    BigDecimal usedRate = loanRate != null && hasCapital ? loanRate : ZERO;
    if (loanRate == null) {
      warnings.add("FINANCING_NOT_INCLUDED");
    } else if (!hasCapital) {
      warnings.add("FINANCING_WITHOUT_ACQUISITION");
    }
    if (target.signum() < 0) warnings.add("NEGATIVE_TARGET_RATE");

    var missing = new ArrayList<>(costs.missing());
    if (loanRate != null && !hasCapital && !missing.contains(ItemType.FINANCING)) {
      missing.add(ItemType.FINANCING);
    }
    if (request.electricity() != null) missing.remove(ItemType.ELECTRICITY);
    if (!missing.isEmpty()) warnings.add("INCOMPLETE_COST_MARGIN_MAY_BE_OVERSTATED");

    var capitalResult =
        CapitalCostCalculator.calculate(
            request.items(),
            request.inputFxKrwPerUsd(),
            usedRate,
            assumptions.depreciationYears(),
            residual,
            scenario);
    var crf = capitalResult.aggregateFactor();
    var capital = capitalResult.annualRecovery();
    residual = capitalResult.residual();
    var annualOperating =
        operating.add(electricityHourly.multiply(YEAR_HOURS, PRECISION), PRECISION);
    var annual = capital.add(annualOperating, PRECISION);
    var allocated = capital.add(operating, PRECISION);
    if (assumptions.calendarMonth() != null) {
      allocated = allocated.divide(BigDecimal.valueOf(12), PRECISION);
    }
    var periodElectricity = electricityHourly.multiply(BigDecimal.valueOf(periodHours), PRECISION);
    var periodCost = allocated.add(periodElectricity, PRECISION);
    var billableFactor =
        assumptions.utilization().multiply(BigDecimal.valueOf(assumptions.gpuCount()));
    var hours = YEAR_HOURS.multiply(billableFactor);
    var billablePeriod = BigDecimal.valueOf(periodHours).multiply(billableFactor);
    var hourly = periodCost.divide(billablePeriod, PRECISION);
    var candidate =
        pricing.method() == PricingMethod.REVENUE_MARGIN
            ? hourly.divide(ONE.subtract(target), PRECISION)
            : hourly.multiply(ONE.add(target), PRECISION);
    if (candidate.signum() <= 0) warnings.add("NON_POSITIVE_CANDIDATE_PRICE");

    validateModel(request.modelId(), "modelId");
    var comparison = compare(request, hourly);
    return new CostSimulationResponseDto(
        "USD/GPU-hour",
        decimal(hourly, 12),
        decimal(candidate, 12),
        candidate.signum() > 0 ? decimal(margin(hourly, candidate), 6) : null,
        pricing.method(),
        decimal(annual, 6),
        decimal(hours, 6),
        decimal(periodCost, 6),
        periodHours,
        decimal(billablePeriod, 6),
        decimal(periodElectricity, 6),
        capitalResult.assetSpecific()
            ? "tco-asset-lives-2"
            : scenario ? "tco-draft-1-scenario" : "tco-draft-1",
        "연 자본회수분 = 각 자산의 (취득비 − 잔존가치 현재가치) × CRF 합계. 정액 감가상각비는 별도 참고이며 중복 합산하지 않음. "
            + "시간 원가 = 기간 원가 ÷ 유료 GPU 시간. "
            + (pricing.method() == PricingMethod.REVENUE_MARGIN
                ? "후보가 = 원가 ÷ (1 − 이익률)."
                : "후보가 = 원가 × (1 + 가산율)."),
        new AppliedAssumptions(
            assumptions.gpuCount(),
            assumptions.depreciationYears(),
            assumptions.utilization().toPlainString(),
            usedRate.toPlainString(),
            !hasCapital ? InterestRateType.NOT_APPLIED : rateType,
            loanRate != null,
            request.inputFxKrwPerUsd() == null ? null : request.inputFxKrwPerUsd().toPlainString(),
            crf == null ? null : decimal(crf, 12),
            decimal(acquisition, 6),
            decimal(annualOperating, 6),
            decimal(operating, 6),
            decimal(capital, 6),
            decimal(residual, 6),
            assumptions.calendarMonth(),
            decimal(capitalResult.annualDepreciation(), 6),
            capitalResult.assets(),
            capitalResult.assetSpecific()),
        missing.isEmpty() ? "COMPLETE" : "INCOMPLETE",
        List.copyOf(missing),
        List.copyOf(warnings),
        comparison,
        false,
        "입력 가정에 따른 시뮬레이션이며 실제 견적이나 확정 판매가격이 아닙니다.");
  }

  record ItemizedCosts(
      BigDecimal acquisition,
      BigDecimal operating,
      boolean hasCapital,
      boolean hasCost,
      Map<ItemType, CostItem> items,
      List<ItemType> missing) {}

  static ItemizedCosts itemizedCosts(List<CostItem> items, BigDecimal fx, boolean scenario) {
    var byType = new EnumMap<ItemType, CostItem>(ItemType.class);
    var index = new EnumMap<ItemType, Integer>(ItemType.class);
    BigDecimal acquisition = ZERO;
    BigDecimal operating = ZERO;
    boolean hasCapital = false;
    boolean hasCost = false;
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      String field = "items[" + i + "]";
      if (byType.putIfAbsent(item.itemType(), item) != null) {
        throw invalid(field + ".itemType", "원가 항목이 중복되었습니다.");
      }
      index.put(item.itemType(), i);
      validateItem(item, field, scenario);
      if (!reflected(item) || item.itemType() == ItemType.FINANCING) {
        continue;
      }

      BigDecimal value = item.amount();
      if (item.currency() == Currency.KRW) {
        if (fx == null) {
          throw invalid("inputFxKrwPerUsd", "원화 비용에는 양수 환율이 필요합니다.");
        }
        value = value.divide(fx, PRECISION);
      }
      hasCost = true;
      if (item.period() == Period.ONE_TIME) {
        hasCapital = true;
        acquisition = acquisition.add(value, PRECISION);
      } else {
        if (item.period() == Period.MONTH)
          value = value.multiply(BigDecimal.valueOf(12), PRECISION);
        operating = operating.add(value, PRECISION);
      }
    }
    validateInclusions(byType, index);
    var missing = new ArrayList<ItemType>();
    for (var entry : REVIEW_ITEMS) {
      if (!byType.containsKey(entry)) missing.add(entry);
    }
    for (var item : byType.values()) {
      if (item.status() == ItemStatus.NOT_INCLUDED) missing.add(item.itemType());
    }
    return new ItemizedCosts(acquisition, operating, hasCapital, hasCost, byType, missing);
  }

  void validateModel(String modelId, String field) {
    if (!catalog.modelExists(modelId(modelId, field))) {
      throw invalid(field, "선택한 GPU 모델이 없습니다.");
    }
  }

  private static void validateItem(CostItem item, String field, boolean scenario) {
    if (item.itemType() == ItemType.FINANCING || !reflected(item)) {
      if (item.amount() != null || item.currency() != null || item.period() != null) {
        throw invalid(field + ".amount", "이 항목에는 금액·통화·기간을 입력할 수 없습니다.");
      }
    } else {
      if (item.amount() == null || item.currency() == null || item.period() == null) {
        throw invalid(field + ".amount", "반영 비용의 금액·통화·기간을 입력하세요.");
      }
      var allowedCapitalTypes = scenario ? CAPITAL_ITEMS : Set.of(ItemType.ACQUISITION);
      if ((item.period() == Period.ONE_TIME && !allowedCapitalTypes.contains(item.itemType()))
          || (item.itemType() == ItemType.ACQUISITION && item.period() != Period.ONE_TIME)
          || (!scenario && item.period() == Period.MONTH)) {
        throw invalid(field + ".period", "원가 항목에 맞는 취득비 또는 운영비 기간을 선택하세요.");
      }
    }
    if (item.status() == ItemStatus.PUBLIC_SOURCE
        && (item.referenceDate() == null || !publicUrl(item.sourceUrl()))) {
      throw invalid(field + ".sourceUrl", "공개 비용은 기준일과 공개 출처 URL이 필요합니다.");
    }
    if (item.status() != ItemStatus.INCLUDED_IN_OTHER && item.includedInItemType() != null) {
      throw invalid(field + ".includedInItemType", "다른 항목에 포함된 비용만 상위 항목을 지정하세요.");
    }
  }

  private static void validateInclusions(
      Map<ItemType, CostItem> items, Map<ItemType, Integer> indexes) {
    for (var item : items.values()) {
      if (item.status() != ItemStatus.INCLUDED_IN_OTHER) continue;
      var parent = items.get(item.includedInItemType());
      if (item.itemType() == ItemType.FINANCING
          || parent == null
          || !reflected(parent)
          || parent.itemType() == ItemType.FINANCING
          || (item.itemType() == ItemType.ELECTRICITY && parent.period() == Period.ONE_TIME)) {
        throw invalid(
            "items[" + indexes.get(item.itemType()) + "].includedInItemType",
            "실제 금액을 반영한 상위 원가 항목을 지정하세요.");
      }
    }
  }

  static void validateFinancing(CostItem item, BigDecimal rate, InterestRateType type) {
    if (item == null || item.status() == ItemStatus.NOT_INCLUDED) {
      if (rate != null || type != InterestRateType.NOT_APPLIED) {
        throw invalid("assumptions.annualInterestRate", "금리를 쓰려면 금융 항목과 금리 종류를 입력하세요.");
      }
    } else if (rate == null
        || (item.status() == ItemStatus.PUBLIC_SOURCE && type != InterestRateType.PUBLIC_BASE)
        || (item.status() == ItemStatus.USER_INPUT
            && type != InterestRateType.ACTUAL_LOAN
            && type != InterestRateType.ASSUMED)) {
      throw invalid("assumptions.annualInterestRate", "금융 항목의 상태와 금리 종류를 맞춰 입력하세요.");
    }
  }

  private MarketComparison compare(CostSimulationRequestDto request, BigDecimal hourly) {
    var filters = request.marketFilters();
    if (filters == null)
      return new MarketComparison("NOT_REQUESTED", "MARKET_FILTERS_NOT_PROVIDED", List.of());

    var groups =
        comparableMarketGroups(request.modelId(), request.assumptions().gpuCount(), filters, false)
            .stream()
            .map(
                group -> {
                  var sources = new LinkedHashMap<String, Map<String, Object>>();
                  var offers =
                      group.offers().stream()
                          .map(
                              offer -> {
                                var ids =
                                    offer.evidence().stream()
                                        .map(
                                            evidence -> {
                                              String id = evidence.get("evidenceId").toString();
                                              sources.putIfAbsent(id, evidence);
                                              return id;
                                            })
                                        .toList();
                                boolean positive = offer.amount().signum() > 0;
                                return new MarketOfferResult(
                                    offer.offerId(),
                                    offer.price(),
                                    positive ? decimal(margin(hourly, offer.amount()), 6) : null,
                                    positive ? "OK" : "UNAVAILABLE",
                                    positive ? null : "NON_POSITIVE_MARKET_PRICE",
                                    offer.collectedAt(),
                                    ids);
                              })
                          .toList();
                  return new MarketGroup(
                      group.key(),
                      group.sampleCounts(),
                      offers,
                      List.copyOf(sources.values()),
                      group.asOf());
                })
            .toList();
    return groups.isEmpty()
        ? new MarketComparison("NOT_AVAILABLE", "SAME_CONDITION_MARKET_PRICE_MISSING", List.of())
        : new MarketComparison("OK", null, groups);
  }

  List<ComparableGroup> comparableMarketGroups(
      String inputModelId, int gpuCount, MarketFilters filters, boolean requirePositive) {
    if (filters.modelId() != null
        && modelId(filters.modelId(), "marketFilters.modelId")
            != modelId(inputModelId, "modelId")) {
      throw invalid("marketFilters.modelId", "원가와 비교 시장의 GPU 모델이 다릅니다.");
    }
    if (filters.countryCode() == null
        || filters.systemType() == null
        || filters.serviceOption() == null
        || filters.gpuCount() == null
        || filters.contractType() == null
        || !present(filters.contractProductType())
        || !present(filters.intraNodeInterconnect())
        || filters.paymentOption() == null) {
      throw new CostInputException(
          "marketFilters", "시장 비교에 필요한 구성·지역·계약 조건을 입력하세요.", "MARKET_CONDITIONS_UNSPECIFIED");
    }
    if (!filters.gpuCount().equals(gpuCount)) {
      throw invalid("marketFilters.gpuCount", "원가와 비교 상품의 GPU 수가 다릅니다.");
    }
    boolean commitment = "COMMITMENT".equals(filters.contractType());
    if (commitment != (filters.commitmentMonths() != null)
        || commitment == "NOT_APPLICABLE".equals(filters.paymentOption())) {
      throw invalid("marketFilters.paymentOption", "약정 기간과 선납 방식이 계약 유형에 맞아야 합니다.");
    }

    var query = new HashMap<String, String>();
    query.put("modelId", inputModelId);
    query.put("countryCode", filters.countryCode());
    query.put("systemType", filters.systemType());
    query.put("serviceOption", filters.serviceOption());
    query.put("gpuCount", filters.gpuCount().toString());
    query.put("contractType", filters.contractType());
    query.put("contractProductType", filters.contractProductType());
    query.put("intraNodeInterconnect", filters.intraNodeInterconnect());
    query.put("paymentOption", filters.paymentOption());
    if (filters.commitmentMonths() != null)
      query.put("commitmentMonths", filters.commitmentMonths().toString());
    if (filters.providerType() != null) query.put("providerType", filters.providerType());
    if (filters.regionCode() != null) query.put("regionCode", filters.regionCode());
    if (filters.providerIds() != null && !filters.providerIds().isEmpty()) {
      query.put("providerIds", String.join(",", filters.providerIds()));
    }

    return market.comparablePrices(query, requirePositive);
  }

  private static BigDecimal electricityHourly(
      CostSimulationRequestDto request, Map<ItemType, CostItem> items) {
    var electricity = request.electricity();
    if (electricity == null) return ZERO;

    var item = items.get(ItemType.ELECTRICITY);
    if (item != null) {
      throw invalid("electricity", "별도 전기료와 전기료 항목을 중복 입력할 수 없습니다.");
    }
    if (request.inputFxKrwPerUsd() == null) {
      throw invalid("inputFxKrwPerUsd", "전기료를 환산할 양수 환율이 필요합니다.");
    }
    return electricity
        .itPowerKw()
        .multiply(electricity.pue(), PRECISION)
        .multiply(electricity.krwPerKwh(), PRECISION)
        .divide(request.inputFxKrwPerUsd(), PRECISION);
  }

  private static BigDecimal capitalRecoveryFactor(BigDecimal rate, int years, BigDecimal growth) {
    if (rate.signum() == 0) return ONE.divide(BigDecimal.valueOf(years), PRECISION);
    return rate.multiply(growth, PRECISION).divide(growth.subtract(ONE), PRECISION);
  }

  private static BigDecimal margin(BigDecimal hourly, BigDecimal price) {
    return price.subtract(hourly).divide(price, PRECISION);
  }

  private static String decimal(BigDecimal number, int scale) {
    return number.setScale(scale, RoundingMode.HALF_UP).toPlainString();
  }

  private static boolean reflected(CostItem item) {
    return item.status() == ItemStatus.PUBLIC_SOURCE || item.status() == ItemStatus.USER_INPUT;
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank() && !value.equalsIgnoreCase("UNKNOWN");
  }

  private static boolean publicUrl(String value) {
    if (value == null) return false;
    try {
      var uri = URI.create(value);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null
          && uri.getUserInfo() == null;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static long modelId(String value, String field) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw invalid(field, "유효한 GPU 모델 ID를 입력하세요.");
    }
  }

  private static CostInputException invalid(String field, String reason) {
    return new CostInputException(field, reason, "VALIDATION_ERROR");
  }

  public static class CostInputException extends RuntimeException {
    private final String field;
    private final String code;

    CostInputException(String field, String reason, String code) {
      super(reason);
      this.field = field;
      this.code = code;
    }

    public String field() {
      return field;
    }

    public String code() {
      return code;
    }
  }
}
