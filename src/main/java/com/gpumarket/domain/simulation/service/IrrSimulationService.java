package com.gpumarket.domain.simulation.service;

import static com.gpumarket.domain.simulation.service.IrrCashFlow.money;

import com.gpumarket.domain.market.service.MarketOfferService.PriceSummary;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ItemType;
import com.gpumarket.domain.simulation.dto.IrrSimulationRequestDto;
import com.gpumarket.domain.simulation.dto.IrrSimulationRequestDto.MarketConstraint;
import com.gpumarket.domain.simulation.dto.IrrSimulationResponseDto;
import com.gpumarket.domain.simulation.dto.IrrSimulationResponseDto.AppliedAssumptions;
import com.gpumarket.domain.simulation.dto.IrrSimulationResponseDto.Candidate;
import com.gpumarket.domain.simulation.dto.IrrSimulationResponseDto.MarketReference;
import com.gpumarket.domain.simulation.dto.IrrSimulationResponseDto.MarketScenario;
import com.gpumarket.domain.simulation.dto.IrrSimulationResponseDto.TargetPrice;
import com.gpumarket.domain.simulation.service.CostSimulationService.CostInputException;
import com.gpumarket.domain.simulation.service.CostSimulationService.ItemizedCosts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IrrSimulationService {
  private static final BigDecimal YEAR_HOURS = BigDecimal.valueOf(8760);
  private static final String UNAVAILABLE = "IRR_UNAVAILABLE";
  private static final String NO_ROOT = "NO_IRR_AT_RATE_ABOVE_MINUS_ONE";
  private static final String APPROXIMATION_FAILED = "IRR_PRECISION_NOT_REACHED";
  private static final String EXCEEDS_CAP = "TARGET_PRICE_ABOVE_MARKET_CAP";
  private final CostSimulationService costService;

  public IrrSimulationService(CostSimulationService costService) {
    this.costService = costService;
  }

  public IrrSimulationResponseDto simulate(IrrSimulationRequestDto request) {
    var input = request.costInput();
    var assumptions = input.assumptions();
    costService.validateModel(input.modelId(), "costInput.modelId");
    ItemizedCosts costs = readCosts(request);
    BigDecimal acquisition = costs.hasCapital() ? costs.acquisition() : null;
    boolean override = request.investment() != null;
    BigDecimal investment = override ? request.investment() : acquisition;
    if (investment == null || investment.signum() <= 0) {
      throw invalid("investment", "양수 투자금을 입력하거나 취득비를 제공하세요.");
    }
    int years =
        request.analysisYears() == null ? assumptions.depreciationYears() : request.analysisYears();
    BigDecimal annualGpuHours =
        YEAR_HOURS
            .multiply(BigDecimal.valueOf(assumptions.gpuCount()))
            .multiply(assumptions.utilization());
    var projection =
        new IrrCashFlow(
            investment, years, request.residualValue(), costs.operating(), annualGpuHours);
    var market = market(request);
    var target = targetPrice(request.targetIrr(), projection, market);

    var candidateResults =
        request.candidatePrices().stream().map(price -> candidate(projection, price)).toList();
    var marketScenarios =
        market.prices().entrySet().stream()
            .map(
                entry ->
                    marketScenario(
                        projection, entry.getKey(), entry.getValue(), market.reference(), target))
            .toList();

    var missing = new ArrayList<>(costs.missing());
    for (var item : List.of(ItemType.GPU, ItemType.MEMORY)) {
      if (!costs.items().containsKey(item)) {
        missing.add(item);
      }
    }
    var warnings = new ArrayList<String>();
    warnings.add("SIMULATION_ONLY");
    warnings.add("FINANCING_NOT_INCLUDED_IN_CASH_FLOW");
    warnings.add("TAX_AND_WORKING_CAPITAL_NOT_INCLUDED");
    if (!missing.isEmpty()) {
      warnings.add("INCOMPLETE_COST");
    }
    if (market.reference().sampleCounts() != null
        && market.reference().sampleCounts().get("providerCount") == 1) {
      warnings.add("SINGLE_PROVIDER");
    }
    var applied =
        new AppliedAssumptions(
            investment.toPlainString(),
            override ? "USER_INPUT" : "ACQUISITION_DEFAULT",
            years,
            request.residualValue().toPlainString(),
            request.targetIrr().toPlainString(),
            assumptions.utilization().toPlainString(),
            assumptions.gpuCount(),
            money(costs.operating()),
            acquisition == null ? null : money(acquisition),
            acquisition == null ? null : investment.compareTo(acquisition) != 0,
            assumptions.interestRateType(),
            assumptions.annualInterestRate() == null
                ? null
                : assumptions.annualInterestRate().toPlainString(),
            false);
    boolean noMarket = "UNAVAILABLE".equals(market.reference().status());
    return new IrrSimulationResponseDto(
        noMarket ? "MARKET_REFERENCE_UNAVAILABLE" : target.status(),
        noMarket ? market.reference().reason() : target.reason(),
        applied,
        candidateResults,
        market.reference(),
        target,
        marketScenarios,
        "irr-draft-1",
        missing.isEmpty() ? "COMPLETE" : "INCOMPLETE",
        List.copyOf(missing),
        List.copyOf(warnings),
        false,
        "입력 가정에 따른 결과이며 실제 투자 수익이나 판매를 보장하지 않습니다.");
  }

  private static ItemizedCosts readCosts(IrrSimulationRequestDto request) {
    var input = request.costInput();
    try {
      var costs =
          CostSimulationService.itemizedCosts(input.items(), input.inputFxKrwPerUsd(), false);
      if (!costs.hasCost()) {
        throw invalid("items", "금액을 반영한 원가 항목이 하나 이상 필요합니다.");
      }
      CostSimulationService.validateFinancing(
          costs.items().get(ItemType.FINANCING),
          input.assumptions().annualInterestRate(),
          input.assumptions().interestRateType());
      return costs;
    } catch (CostInputException ex) {
      throw new CostInputException("costInput." + ex.field(), ex.getMessage(), ex.code());
    }
  }

  private record MarketBasis(MarketReference reference, Map<MarketConstraint, BigDecimal> prices) {}

  private MarketBasis market(IrrSimulationRequestDto request) {
    var constraint = request.marketConstraint();
    if (constraint == MarketConstraint.NONE) {
      if (request.marketFilters() != null) {
        throw invalid("marketFilters", "시장 제약을 사용할 때만 비교 조건을 입력하세요.");
      }
      return new MarketBasis(
          new MarketReference(constraint, "NOT_REQUESTED", null, null, null, List.of()), Map.of());
    }
    if (request.marketFilters() == null) {
      throw new CostInputException(
          "marketFilters", "시장 비교에 필요한 구성·지역·계약 조건을 입력하세요.", "MARKET_CONDITIONS_UNSPECIFIED");
    }

    var groups =
        costService.comparableMarketGroups(
            request.costInput().modelId(),
            request.costInput().assumptions().gpuCount(),
            request.marketFilters(),
            true);
    if (groups.size() != 1) {
      return new MarketBasis(
          new MarketReference(
              constraint,
              "UNAVAILABLE",
              null,
              groups.isEmpty()
                  ? "SAME_CONDITION_MARKET_PRICE_MISSING"
                  : "MULTIPLE_COMPARISON_GROUPS",
              null,
              List.of()),
          Map.of());
    }

    var group = groups.get(0);
    var pricesByProvider = new HashMap<Long, BigDecimal>();
    var sources = new LinkedHashMap<String, Map<String, Object>>();
    for (var offer : group.offers()) {
      pricesByProvider.merge(offer.providerId(), offer.amount(), BigDecimal::min);
      for (var source : offer.evidence()) {
        sources.putIfAbsent(source.get("evidenceId").toString(), source);
      }
    }
    var summary = PriceSummary.fromRepresentatives(pricesByProvider.values());
    var prices = new EnumMap<MarketConstraint, BigDecimal>(MarketConstraint.class);
    prices.put(MarketConstraint.MINIMUM, summary.minimum());
    prices.put(MarketConstraint.MEDIAN, summary.median());
    prices.put(MarketConstraint.MAXIMUM, summary.maximum());
    return new MarketBasis(
        new MarketReference(
            constraint,
            "OK",
            prices.get(constraint).toPlainString(),
            null,
            group.sampleCounts(),
            List.copyOf(sources.values())),
        prices);
  }

  private static TargetPrice targetPrice(
      BigDecimal targetIrr, IrrCashFlow projection, MarketBasis market) {
    if ("UNAVAILABLE".equals(market.reference().status())) {
      return new TargetPrice(null, "NOT_EVALUATED", "MARKET_REFERENCE_UNAVAILABLE", null);
    }

    BigDecimal price = projection.minimumPrice(targetIrr);
    BigDecimal cap = market.prices().get(market.reference().type());
    if (cap != null && price.compareTo(cap) > 0) {
      return new TargetPrice(null, "NO_FEASIBLE_PRICE", EXCEEDS_CAP, null);
    }

    String irr = projection.irr(price);
    if (irr == null) {
      return new TargetPrice(null, UNAVAILABLE, irrFailure(projection, price), null);
    }
    return new TargetPrice(price.toPlainString(), "OK", null, irr);
  }

  private static Candidate candidate(IrrCashFlow projection, BigDecimal price) {
    String irr = projection.irr(price);
    return new Candidate(
        price.toPlainString(),
        irr == null ? UNAVAILABLE : "OK",
        irr == null ? irrFailure(projection, price) : null,
        projection.schedule(price),
        irr);
  }

  private static MarketScenario marketScenario(
      IrrCashFlow projection,
      MarketConstraint type,
      BigDecimal price,
      MarketReference reference,
      TargetPrice target) {
    String irr = projection.irr(price);
    String reason = irr == null ? irrFailure(projection, price) : null;
    if (type == reference.type() && EXCEEDS_CAP.equals(target.reason()) && reason == null) {
      reason = EXCEEDS_CAP;
    }
    return new MarketScenario(
        type,
        price.toPlainString(),
        irr,
        irr == null ? UNAVAILABLE : "OK",
        reason,
        reference.sampleCounts(),
        reference.sources());
  }

  private static String irrFailure(IrrCashFlow projection, BigDecimal price) {
    return projection.hasPositiveFinalFlow(price) ? APPROXIMATION_FAILED : NO_ROOT;
  }

  private static CostInputException invalid(String field, String reason) {
    return new CostInputException(field, reason, "VALIDATION_ERROR");
  }
}
