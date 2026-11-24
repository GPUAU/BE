package com.gpumarket.domain.simulation.service;

import com.gpumarket.domain.simulation.dto.DataCenterSimulationRequest;
import com.gpumarket.domain.simulation.dto.DataCenterSimulationRequest.Asset;
import com.gpumarket.domain.simulation.dto.DataCenterSimulationRequest.CapitalMethod;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DataCenterSimulationService {
  private static final MathContext MC = MathContext.DECIMAL128;
  private static final BigDecimal HOURS = new BigDecimal("8760");
  private final CostSimulationService cost;

  public DataCenterSimulationService(CostSimulationService cost) {
    this.cost = cost;
  }

  public Map<String, Object> simulate(DataCenterSimulationRequest input) {
    var assets = List.of(input.server(), input.network(), input.facility());
    var labels = List.of("SERVER", "NETWORK", "FACILITY");
    var baselineCapex =
        List.of(
            new BigDecimal("20833333333"),
            new BigDecimal("5000000000"),
            new BigDecimal("12166666667"));
    var baselineAnnual =
        List.of(
            new BigDecimal("5000000000"),
            new BigDecimal("1200000000"),
            new BigDecimal("1400000000"));
    BigDecimal capex = BigDecimal.ZERO, capital = BigDecimal.ZERO, depreciation = BigDecimal.ZERO;
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < assets.size(); i++) {
      var asset = assets.get(i);
      if (asset.residualUsd().compareTo(asset.capexUsd()) > 0)
        fail(labels.get(i), "잔존가치는 취득비 이하여야 합니다.");
      boolean report = input.capitalMethod() == CapitalMethod.REPORT_FACTORS;
      if (report && (asset.years() != (i == 2 ? 14 : 5) || asset.residualUsd().signum() != 0)) {
        fail(labels.get(i), "보고서 계수 모드는 IT 5년·시설 14년·잔존가치 0 가정입니다. 변경하려면 할인율 계산을 선택하세요.");
      }
      var annual =
          report
              ? asset
                  .capexUsd()
                  .multiply(baselineAnnual.get(i), MC)
                  .divide(baselineCapex.get(i), MC)
              : annualRecovery(asset, input.annualDiscountRate());
      var straight =
          asset
              .capexUsd()
              .subtract(asset.residualUsd())
              .divide(BigDecimal.valueOf(asset.years()), MC);
      capex = capex.add(asset.capexUsd());
      capital = capital.add(annual);
      depreciation = depreciation.add(straight);
      rows.add(
          Map.of(
              "type",
              labels.get(i),
              "capexUsd",
              decimal(asset.capexUsd()),
              "annualCapitalUsd",
              decimal(annual),
              "annualDepreciationUsd",
              decimal(straight),
              "years",
              asset.years()));
    }
    var racks =
        BigDecimal.valueOf(input.gpuCount()).divide(BigDecimal.valueOf(input.gpusPerRack()), MC);
    var itKw = racks.multiply(input.rackPowerKw(), MC);
    var gridKw = itKw.multiply(input.pue(), MC);
    var energyKwh = gridKw.multiply(input.powerLoadFraction(), MC).multiply(HOURS, MC);
    var electricity = energyKwh.multiply(input.electricityUsdPerKwh(), MC);
    var opex = electricity.add(input.otherAnnualOpexUsd());
    var tco = capital.add(opex);
    var soldHours =
        BigDecimal.valueOf(input.gpuCount()).multiply(HOURS).multiply(input.utilization());
    var unit = tco.divide(soldHours, MC);
    var candidate = unit.divide(BigDecimal.ONE.subtract(input.targetMargin()), MC);
    var gpuRevenue = soldHours.multiply(input.bareMetalPrice());
    var softwareRevenue = gpuRevenue.multiply(input.softwareAttach());
    var totalRevenue = gpuRevenue.add(softwareRevenue);
    var gpuSurplus = gpuRevenue.subtract(tco);
    var surplus = totalRevenue.subtract(tco);
    var r = new LinkedHashMap<String, Object>();
    r.put("formulaVersion", "dc-report-1");
    r.put("saved", false);
    r.put("unit", "USD/GPU-hour");
    r.put("capitalMethod", input.capitalMethod());
    r.put("assets", rows);
    r.put("equivalentRacks", decimal(racks));
    r.put("installedRacks", racks.setScale(0, RoundingMode.CEILING).longValueExact());
    r.put("itLoadMw", decimal(itKw.movePointLeft(3)));
    r.put("gridLoadMw", decimal(gridKw.movePointLeft(3)));
    r.put("energyMwh", decimal(energyKwh.movePointLeft(3)));
    r.put("annualElectricityUsd", decimal(electricity));
    r.put("capexUsd", decimal(capex));
    r.put("annualCapitalUsd", decimal(capital));
    r.put("annualDepreciationUsd", decimal(depreciation));
    r.put("annualOpexUsd", decimal(opex));
    r.put("annualTcoUsd", decimal(tco));
    r.put("billableGpuHours", decimal(soldHours));
    r.put("hourlyCost", decimal(unit));
    r.put("candidatePrice", decimal(candidate));
    r.put(
        "bareMetalBreakEvenWithAttach",
        decimal(unit.divide(BigDecimal.ONE.add(input.softwareAttach()), MC)));
    r.put("gpuRevenueUsd", decimal(gpuRevenue));
    r.put("softwareRevenueUsd", decimal(softwareRevenue));
    r.put("totalRevenueUsd", decimal(totalRevenue));
    r.put("gpuEconomicSurplusUsd", decimal(gpuSurplus));
    r.put("economicSurplusUsd", decimal(surplus));
    r.put(
        "gpuEconomicMargin",
        gpuRevenue.signum() == 0 ? null : decimal(gpuSurplus.divide(gpuRevenue, MC)));
    r.put(
        "economicMarginWithAttach",
        totalRevenue.signum() == 0 ? null : decimal(surplus.divide(totalRevenue, MC)));
    r.put("marketComparison", cost.compareDataCenter(input.modelId(), input.marketFilters(), unit));
    return r;
  }

  private static BigDecimal annualRecovery(Asset a, BigDecimal r) {
    if (r.signum() == 0)
      return a.capexUsd().subtract(a.residualUsd()).divide(BigDecimal.valueOf(a.years()), MC);
    var growth = BigDecimal.ONE.add(r).pow(a.years(), MC);
    var factor = r.multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC);
    return a.capexUsd().subtract(a.residualUsd().divide(growth, MC)).multiply(factor, MC);
  }

  private static String decimal(BigDecimal n) {
    return n.setScale(8, RoundingMode.HALF_UP).toPlainString();
  }

  private static void fail(String field, String reason) {
    throw new CostSimulationService.CostInputException(field, reason, "VALIDATION_ERROR");
  }
}
