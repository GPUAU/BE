package com.gpumarket.domain.simulation.montecarlo;

import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Investment;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.PricingMode;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloPricing.ScenarioInputs;

final class MonteCarloCashFlow {
  static final int HOURS_PER_MONTH = 730;
  private static final double IRR_LOW_MONTHLY = -0.99;
  private static final double IRR_HIGH_MONTHLY = 1.0;
  private static final double IRR_NPV_TOLERANCE = 1e-10;
  private static final double IRR_RATE_TOLERANCE = 1e-12;

  private final double[] revenueAtUnitPrice;
  private final double[] cost;

  private MonteCarloCashFlow(double[] revenueAtUnitPrice, double[] cost) {
    this.revenueAtUnitPrice = revenueAtUnitPrice;
    this.cost = cost;
  }

  static MonteCarloCashFlow project(Investment investment, ScenarioInputs inputs, double[] path) {
    int months = investment.months();
    int gpuCount = investment.gpus();
    boolean followsMarket = investment.pricingMode() == PricingMode.FOLLOW_MARKET;
    var revenueAtUnitPrice = new double[months + 1];
    var cost = new double[months + 1];

    double capitalCost = gpuCount * investment.capexPerGpu() * (1.0 + inputs.capexOverrun());
    double initialOpex = gpuCount * inputs.opexPerGpuMonth();
    double monthlyOpexGrowth = Math.pow(1.0 + inputs.opexAnnualInflation(), 1.0 / 12.0);
    double fullUtilizationRevenue = gpuCount * HOURS_PER_MONTH * inputs.utilization();
    cost[0] = capitalCost;

    double monthlyOpex = initialOpex;
    for (int month = 1; month <= months; month++) {
      double ramp =
          investment.rampMonths() == 0
              ? 1.0
              : Math.min(1.0, (double) month / investment.rampMonths());
      double priceFactor = followsMarket ? path[month] : 1.0;
      revenueAtUnitPrice[month] = fullUtilizationRevenue * ramp * priceFactor;
      cost[month] = monthlyOpex;
      monthlyOpex *= monthlyOpexGrowth;
    }

    cost[months] -= capitalCost * investment.residualValuePct();
    return new MonteCarloCashFlow(revenueAtUnitPrice, cost);
  }

  double requiredPrice(double targetIrrAnnual) {
    double discountFactor = Math.pow(1.0 + targetIrrAnnual, 1.0 / 12.0);
    double presentRevenue = presentValue(revenueAtUnitPrice, discountFactor);
    double required = presentValue(cost, discountFactor) / presentRevenue;
    if (presentRevenue <= 0.0 || !Double.isFinite(required)) {
      throw new IllegalStateException("선택한 경로의 목표 IRR 기준 판매가를 계산할 수 없습니다.");
    }
    return required;
  }

  Double annualIrrAt(double price) {
    double[] flows = new double[cost.length];
    for (int month = 0; month < flows.length; month++) {
      flows[month] = price * revenueAtUnitPrice[month] - cost[month];
    }

    double lowerRate = IRR_LOW_MONTHLY;
    double upperRate = IRR_HIGH_MONTHLY;
    double lowerNpv = presentValue(flows, 1.0 + lowerRate);
    double upperNpv = presentValue(flows, 1.0 + upperRate);
    if (lowerNpv == 0.0) {
      return annualRate(lowerRate);
    }
    if (upperNpv == 0.0) {
      return annualRate(upperRate);
    }
    if ((lowerNpv > 0.0) == (upperNpv > 0.0)) {
      return null;
    }

    for (int iteration = 0; iteration < 200; iteration++) {
      double midpoint = (lowerRate + upperRate) / 2.0;
      double midpointNpv = presentValue(flows, 1.0 + midpoint);
      if (Math.abs(midpointNpv) < IRR_NPV_TOLERANCE || upperRate - lowerRate < IRR_RATE_TOLERANCE) {
        return annualRate(midpoint);
      }
      if ((lowerNpv > 0.0) != (midpointNpv > 0.0)) {
        upperRate = midpoint;
      } else {
        lowerRate = midpoint;
        lowerNpv = midpointNpv;
      }
    }
    return annualRate((lowerRate + upperRate) / 2.0);
  }

  private static double presentValue(double[] flows, double discountFactor) {
    double value = 0.0;
    for (int month = flows.length - 1; month >= 0; month--) {
      value = flows[month] + value / discountFactor;
    }
    return value;
  }

  private static double annualRate(double monthly) {
    return Math.pow(1.0 + monthly, 12.0) - 1.0;
  }
}
