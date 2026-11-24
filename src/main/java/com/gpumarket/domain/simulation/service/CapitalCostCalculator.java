package com.gpumarket.domain.simulation.service;

import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.CostItem;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.Currency;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ItemStatus;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.Period;
import com.gpumarket.domain.simulation.dto.CostSimulationResponseDto.CapitalAsset;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

final class CapitalCostCalculator {
  private static final MathContext MC = MathContext.DECIMAL128;
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final BigDecimal ONE = BigDecimal.ONE;

  record Result(
      BigDecimal annualRecovery,
      BigDecimal annualDepreciation,
      BigDecimal residual,
      BigDecimal aggregateFactor,
      List<CapitalAsset> assets,
      boolean assetSpecific) {}

  static Result calculate(
      List<CostItem> items,
      BigDecimal fx,
      BigDecimal rate,
      int commonYears,
      BigDecimal commonResidual,
      boolean scenario) {
    boolean detailed =
        items.stream().anyMatch(i -> i.usefulLifeYears() != null || i.residualValueUsd() != null);
    if (detailed && !scenario) fail("items", "자산별 내용연수는 SCREEN_SCENARIO에서 사용하세요.");
    if (detailed && commonResidual.signum() != 0)
      fail("assumptions.residualValueUsd", "자산별 잔존가치와 공통 잔존가치를 중복 입력할 수 없습니다.");
    BigDecimal total = ZERO, recovery = ZERO, depreciation = ZERO, residual = ZERO;
    var assets = new ArrayList<CapitalAsset>();
    for (int index = 0; index < items.size(); index++) {
      var item = items.get(index);
      boolean capital =
          (item.status() == ItemStatus.USER_INPUT || item.status() == ItemStatus.PUBLIC_SOURCE)
              && item.period() == Period.ONE_TIME;
      boolean hasDetails = item.usefulLifeYears() != null || item.residualValueUsd() != null;
      String field = "items[" + index + "]";
      if (!capital) {
        if (hasDetails) fail(field + ".usefulLifeYears", "별도 반영하는 일회성 투자에만 내용연수·잔존가치를 입력하세요.");
        continue;
      }
      BigDecimal amount =
          item.currency() == Currency.KRW ? item.amount().divide(fx, MC) : item.amount();
      total = total.add(amount, MC);
      if (!detailed) continue;
      if (item.usefulLifeYears() == null || item.residualValueUsd() == null)
        fail(field + ".usefulLifeYears", "모든 일회성 투자에 내용연수와 잔존가치를 입력하세요. 0도 명시하세요.");
      int years = item.usefulLifeYears();
      BigDecimal salvage = item.residualValueUsd();
      if (years < 1 || years > 100 || salvage.signum() < 0 || salvage.compareTo(amount) > 0)
        fail(field + ".residualValueUsd", "내용연수는 1~100년, 잔존가치는 해당 취득비(USD) 이하여야 합니다.");
      BigDecimal factor = factor(rate, years);
      BigDecimal annual =
          amount.subtract(salvage.divide(ONE.add(rate).pow(years, MC), MC)).multiply(factor, MC);
      BigDecimal straight = amount.subtract(salvage).divide(BigDecimal.valueOf(years), MC);
      recovery = recovery.add(annual, MC);
      depreciation = depreciation.add(straight, MC);
      residual = residual.add(salvage, MC);
      assets.add(
          new CapitalAsset(
              item.itemType(),
              years,
              decimal(amount),
              decimal(salvage),
              factor.toPlainString(),
              decimal(annual),
              decimal(straight)));
    }
    if (!detailed) {
      var factor = factor(rate, commonYears);
      recovery =
          total
              .subtract(commonResidual.divide(ONE.add(rate).pow(commonYears, MC), MC))
              .multiply(factor, MC);
      depreciation = total.subtract(commonResidual).divide(BigDecimal.valueOf(commonYears), MC);
      assets.add(
          new CapitalAsset(
              null,
              commonYears,
              decimal(total),
              decimal(commonResidual),
              factor.toPlainString(),
              decimal(recovery),
              decimal(depreciation)));
      return new Result(recovery, depreciation, commonResidual, factor, List.copyOf(assets), false);
    }
    return new Result(recovery, depreciation, residual, null, List.copyOf(assets), true);
  }

  private static BigDecimal factor(BigDecimal rate, int years) {
    if (rate.signum() == 0) return ONE.divide(BigDecimal.valueOf(years), MC);
    var growth = ONE.add(rate).pow(years, MC);
    return rate.multiply(growth, MC).divide(growth.subtract(ONE), MC);
  }

  private static String decimal(BigDecimal value) {
    return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
  }

  private static void fail(String field, String message) {
    throw new CostSimulationService.CostInputException(field, message, "VALIDATION_ERROR");
  }
}
