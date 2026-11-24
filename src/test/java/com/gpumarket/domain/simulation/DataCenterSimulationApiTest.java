package com.gpumarket.domain.simulation;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gpumarket.domain.catalog.repository.CatalogRepository;
import com.gpumarket.domain.market.service.MarketOfferService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:dc-simulations",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DataCenterSimulationApiTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockBean CatalogRepository catalog;
  @MockBean MarketOfferService market;

  ObjectNode example() throws Exception {
    return (ObjectNode)
        json.readTree(
            """
{"gpuCount":600000,"gpusPerRack":72,"rackPowerKw":"120","pue":"1.14",
 "utilization":"0.75","powerLoadFraction":"0.75","electricityUsdPerKwh":"0.0834",
 "otherAnnualOpexUsd":"275350680","capitalMethod":"REPORT_FACTORS","annualDiscountRate":"0.08",
 "server":{"capexUsd":"20833333333","years":5,"residualUsd":"0"},
 "network":{"capexUsd":"5000000000","years":5,"residualUsd":"0"},
 "facility":{"capexUsd":"12166666667","years":14,"residualUsd":"0"},
 "targetMargin":"0.2","bareMetalPrice":"3","softwareAttach":"0.1"}
""");
  }

  ResultActions submit(ObjectNode input) throws Exception {
    return mvc.perform(
        post("/api/v1/data-center-simulations")
            .contentType("application/json")
            .content(json.writeValueAsBytes(input)));
  }

  @Test
  void reproducesReportWithoutDoubleCountingPowerOrDepreciation() throws Exception {
    submit(example())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.itLoadMw").value("1000.00000000"))
        .andExpect(jsonPath("$.result.gridLoadMw").value("1140.00000000"))
        .andExpect(jsonPath("$.result.installedRacks").value(8334))
        .andExpect(jsonPath("$.result.annualElectricityUsd").value("624649320.00000000"))
        .andExpect(jsonPath("$.result.annualOpexUsd").value("900000000.00000000"))
        .andExpect(jsonPath("$.result.capexUsd").value("38000000000.00000000"))
        .andExpect(jsonPath("$.result.annualCapitalUsd").value("7600000000.00000000"))
        .andExpect(jsonPath("$.result.annualTcoUsd").value("8500000000.00000000"))
        .andExpect(jsonPath("$.result.hourlyCost").value("2.15626585"))
        .andExpect(jsonPath("$.result.candidatePrice").value("2.69533232"))
        .andExpect(jsonPath("$.result.totalRevenueUsd").value("13008600000.00000000"))
        .andExpect(jsonPath("$.result.economicSurplusUsd").value("4508600000.00000000"))
        .andExpect(jsonPath("$.result.saved").value(false));
  }

  @Test
  void energyLoadAndPaidUtilizationAreIndependentInputs() throws Exception {
    var input = example();
    input.put("utilization", "0.5");
    submit(input)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.annualElectricityUsd").value("624649320.00000000"))
        .andExpect(jsonPath("$.result.billableGpuHours").value("2628000000.00000000"));
  }

  @Test
  void customZeroRateUsesIndividualLifeAndSalvage() throws Exception {
    var input = example();
    input.put("capitalMethod", "DISCOUNT_RATE").put("annualDiscountRate", "0");
    ((ObjectNode) input.path("server"))
        .put("capexUsd", "400000")
        .put("residualUsd", "50000")
        .put("years", 5);
    ((ObjectNode) input.path("network")).put("capexUsd", "0");
    ((ObjectNode) input.path("facility")).put("capexUsd", "140000");
    submit(input)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.annualCapitalUsd").value("80000.00000000"));
  }

  @Test
  void rejectsIgnoredReportAssumptionsAndInvalidValues() throws Exception {
    var input = example();
    ((ObjectNode) input.path("server")).put("years", 3);
    submit(input).andExpect(status().isUnprocessableEntity());
    input = example();
    input.put("targetMargin", "1");
    submit(input).andExpect(status().isUnprocessableEntity());
    input = example();
    input.put("gpuCount", 0);
    submit(input).andExpect(status().isUnprocessableEntity());
    input = example();
    ((ObjectNode) input.path("facility")).put("residualUsd", "999999999999");
    submit(input).andExpect(status().isUnprocessableEntity());
  }

  @Test
  void comparesEightGpuOfferAgainstWholeDataCenterUnitCost() throws Exception {
    when(catalog.modelExists(2L)).thenReturn(true);
    when(market.comparablePrices(anyMap(), eq(false))).thenReturn(List.of());
    var input = example();
    input.put("modelId", "2");
    input
        .putObject("marketFilters")
        .put("countryCode", "US")
        .put("systemType", "INSTANCE")
        .put("serviceOption", "STANDARD")
        .put("gpuCount", 8)
        .put("contractType", "ON_DEMAND")
        .put("contractProductType", "ON_DEMAND")
        .put("intraNodeInterconnect", "NVLink")
        .put("paymentOption", "NOT_APPLICABLE");
    submit(input)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.marketComparison.status").value("NOT_AVAILABLE"));
    verify(market).comparablePrices(argThat(q -> "8".equals(q.get("gpuCount"))), eq(false));
  }
}
