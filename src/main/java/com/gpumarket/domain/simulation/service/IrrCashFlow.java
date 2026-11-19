package com.gpumarket.domain.simulation.service;

import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.ZERO;

import com.gpumarket.domain.simulation.dto.IrrSimulationResponseDto.CashFlow;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

record IrrCashFlow(
    BigDecimal investment,
    int years,
    BigDecimal residual,
    BigDecimal operatingCost,
    BigDecimal annualGpuHours) {
  private static final BigDecimal TWO = BigDecimal.valueOf(2);
  private static final MathContext PRECISION = MathContext.DECIMAL128;
  private static final BigDecimal FACTOR_TOLERANCE = new BigDecimal("0.00000000000000000001");
  private static final int RESULT_SCALE = 12;

  BigDecimal minimumPrice(BigDecimal targetIrr) {
    BigDecimal growth = ONE;
    BigDecimal discountWeight = ONE;
    BigDecimal factor = ONE.add(targetIrr);

    // Multiplying each NPV term by (1 + targetIrr)^years avoids division in the sum.
    for (int year = 1; year < years; year++) {
      growth = growth.multiply(factor);
      discountWeight = discountWeight.add(growth);
    }
    growth = growth.multiply(factor);

    BigDecimal requiredRevenue =
        investment.multiply(growth).add(operatingCost.multiply(discountWeight)).subtract(residual);
    return requiredRevenue
        .max(ZERO)
        .divide(annualGpuHours.multiply(discountWeight), RESULT_SCALE, RoundingMode.CEILING);
  }

  List<CashFlow> schedule(BigDecimal price) {
    BigDecimal annual = annualNet(price);
    var cashFlows = new ArrayList<CashFlow>();
    cashFlows.add(new CashFlow(0, money(investment.negate())));

    for (int year = 1; year < years; year++) {
      cashFlows.add(new CashFlow(year, money(annual)));
    }
    cashFlows.add(new CashFlow(years, money(annual.add(residual))));
    return List.copyOf(cashFlows);
  }

  String irr(BigDecimal price) {
    BigDecimal annual = annualNet(price);
    BigDecimal finalFlow = annual.add(residual);

    // Interim flows are constant and the nonnegative residual can only increase the final flow.
    // A positive final flow therefore guarantees exactly one change of sign.
    if (finalFlow.signum() <= 0) {
      return null;
    }
    if (npvNumerator(ONE, annual, finalFlow).signum() == 0) {
      return ZERO.setScale(RESULT_SCALE).toPlainString();
    }

    BigDecimal positiveFlows =
        annual.max(ZERO).multiply(BigDecimal.valueOf(years - 1L)).add(finalFlow);
    BigDecimal lower = ZERO;
    BigDecimal upper = ONE.add(positiveFlows.divide(investment, PRECISION)).max(ONE);

    // For a factor >= 1, the PV of positive flows is at most positiveFlows / factor.
    for (int iteration = 0; iteration < 256; iteration++) {
      BigDecimal middle = lower.add(upper).divide(TWO);
      int sign = npvNumerator(middle, annual, finalFlow).signum();

      if (sign == 0 || upper.subtract(lower).compareTo(FACTOR_TOLERANCE) <= 0) {
        return formatIrr(middle);
      }
      if (sign > 0) {
        lower = middle;
      } else {
        upper = middle;
      }
    }
    return null;
  }

  boolean hasPositiveFinalFlow(BigDecimal price) {
    return annualNet(price).add(residual).signum() > 0;
  }

  private BigDecimal annualNet(BigDecimal price) {
    return price.multiply(annualGpuHours).subtract(operatingCost);
  }

  private BigDecimal npvNumerator(BigDecimal factor, BigDecimal annual, BigDecimal finalFlow) {
    BigDecimal result = investment.negate();
    for (int year = 1; year < years; year++) {
      result = result.multiply(factor, PRECISION).add(annual, PRECISION);
    }
    return result.multiply(factor, PRECISION).add(finalFlow, PRECISION);
  }

  private static String formatIrr(BigDecimal factor) {
    BigDecimal rate = factor.subtract(ONE).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    if (rate.compareTo(ONE.negate()) == 0) {
      rate = rate.add(ONE.movePointLeft(RESULT_SCALE));
    }
    return rate.toPlainString();
  }

  static String money(BigDecimal value) {
    var amount = value.stripTrailingZeros();
    return amount.setScale(Math.max(6, amount.scale())).toPlainString();
  }
}
