package com.gpumarket.domain.simulation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gpumarket.domain.catalog.repository.CatalogRepository;
import com.gpumarket.domain.market.service.MarketOfferService;
import org.junit.jupiter.api.BeforeEach;
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
      "spring.datasource.url=jdbc:h2:mem:cost-simulations",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CostSimulationApiTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockBean CatalogRepository catalog;
  @MockBean MarketOfferService market;

  @BeforeEach
  void knownModel() {
    when(catalog.modelExists(2L)).thenReturn(true);
  }

  @Test
  void calculatesAnnualCostAndCandidateUsingTheSpecificationExample() throws Exception {
    submit(example())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.hourlyCost").value("2.323874755382"))
        .andExpect(jsonPath("$.result.candidatePrice").value("2.904843444227"))
        .andExpect(jsonPath("$.result.estimatedMarginRate").value("0.200000"))
        .andExpect(jsonPath("$.result.annualCost").value("114000.000000"))
        .andExpect(jsonPath("$.result.billableGpuHoursPerYear").value("49056.000000"))
        .andExpect(jsonPath("$.result.marketComparison.status").value("NOT_REQUESTED"))
        .andExpect(jsonPath("$.result.costCompleteness").value("INCOMPLETE"))
        .andExpect(jsonPath("$.result.saved").value(false));
  }

  @Test
  void rejectsMissingFxAndInvalidUtilizationWithoutEchoingInputs() throws Exception {
    var krw = example();
    ((ObjectNode) krw.path("items").get(0)).put("currency", "KRW");
    submit(krw)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("inputFxKrwPerUsd"));
    var invalid = example();
    ((ObjectNode) invalid.path("assumptions")).put("utilization", "1.2");
    submit(invalid)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("assumptions.utilization"));
  }

  @Test
  void rejectsFractionalGpuCountWithoutRoundingIt() throws Exception {
    var input = example();
    ((ObjectNode) input.path("assumptions")).put("gpuCount", 8.5);
    submit(input).andExpect(status().isBadRequest());
  }

  @Test
  void separatesUnspecifiedMarketConditionsFromAnOmittedComparison() throws Exception {
    var input = example();
    input.putObject("marketFilters").put("countryCode", "US");
    submit(input)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("MARKET_CONDITIONS_UNSPECIFIED"));
  }

  @Test
  void preservesMonthlyScenarioWithResidualValueAndSeparatelyMeteredElectricity() throws Exception {
    var input = example();
    ((ObjectNode) input.path("assumptions"))
        .put("costProfile", "SCREEN_SCENARIO")
        .put("calendarMonth", "2028-02")
        .put("residualValueUsd", "100000");
    input.put("inputFxKrwPerUsd", "1000");
    input.putObject("electricity").put("itPowerKw", "10").put("pue", "1.2").put("krwPerKwh", "100");
    submit(input)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.periodHours").value(696))
        .andExpect(jsonPath("$.result.periodElectricityCost").value("835.200000"))
        .andExpect(jsonPath("$.result.annualCost").value("104512.000000"))
        .andExpect(jsonPath("$.result.billableGpuHoursForPeriod").value("3897.600000"))
        .andExpect(
            jsonPath("$.result.appliedAssumptions.annualOperatingCostUsd").value("44512.000000"));
  }

  @Test
  void rejectsFinancingWithoutItsRateAndHiddenAgentFields() throws Exception {
    var financing = example();
    ((ArrayNode) financing.path("items"))
        .addObject()
        .put("itemType", "FINANCING")
        .put("status", "USER_INPUT");
    submit(financing)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("assumptions.annualInterestRate"));
    var unknown = example().put("message", "외부 전송");
    submit(unknown)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void separatesAssetLivesAndDoesNotAddDepreciationToCapitalRecovery() throws Exception {
    var input = assetExample();
    submit(input)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.annualCost").value("124000.000000"))
        .andExpect(
            jsonPath("$.result.appliedAssumptions.annualCapitalRecoveryUsd").value("90000.000000"))
        .andExpect(
            jsonPath("$.result.appliedAssumptions.annualStraightLineDepreciationUsd")
                .value("90000.000000"))
        .andExpect(jsonPath("$.result.appliedAssumptions.assetSpecificLives").value(true))
        .andExpect(
            jsonPath("$.result.appliedAssumptions.capitalAssets[1].usefulLifeYears").value(14))
        .andExpect(jsonPath("$.result.formulaVersion").value("tco-asset-lives-2"));
  }

  @Test
  void validatesResidualAgainstEachAssetAndRejectsMixedOrHiddenAssumptions() throws Exception {
    var tooHigh = assetExample();
    ((ObjectNode) tooHigh.path("items").get(3)).put("residualValueUsd", "140001");
    submit(tooHigh).andExpect(status().isUnprocessableEntity());
    var mixed = assetExample();
    ((ObjectNode) mixed.path("items").get(3)).remove("usefulLifeYears");
    submit(mixed).andExpect(status().isUnprocessableEntity());
    var doubleResidual = assetExample();
    ((ObjectNode) doubleResidual.path("assumptions")).put("residualValueUsd", "100");
    submit(doubleResidual).andExpect(status().isUnprocessableEntity());
    var hidden = assetExample();
    ((ObjectNode) hidden.path("items").get(2)).put("usefulLifeYears", 5);
    submit(hidden).andExpect(status().isUnprocessableEntity());
    var fractional = assetExample();
    ((ObjectNode) fractional.path("items").get(0)).put("usefulLifeYears", 4.5);
    submit(fractional).andExpect(status().isBadRequest());
  }

  @Test
  void handlesAssetResidualsInUsdAfterKrwConversion() throws Exception {
    var input = assetExample();
    input.put("inputFxKrwPerUsd", "1000");
    ((ObjectNode) input.path("items").get(3))
        .put("amount", "140000000")
        .put("currency", "KRW")
        .put("residualValueUsd", "14000");
    submit(input)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.result.appliedAssumptions.acquisitionCostUsd").value("540000.000000"))
        .andExpect(
            jsonPath("$.result.appliedAssumptions.annualCapitalRecoveryUsd").value("89000.000000"));
  }

  @Test
  void preservesMonthlyElectricityAndFinancingWithDifferentAssetLives() throws Exception {
    var input = assetExample();
    ((ObjectNode) input.path("assumptions"))
        .put("calendarMonth", "2028-02")
        .put("annualInterestRate", "0.1")
        .put("interestRateType", "ASSUMED");
    ((ArrayNode) input.path("items"))
        .addObject()
        .put("itemType", "FINANCING")
        .put("status", "USER_INPUT");
    input.put("inputFxKrwPerUsd", "1000");
    input.putObject("electricity").put("itPowerKw", "10").put("pue", "1.2").put("krwPerKwh", "100");
    var result =
        submit(input)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.periodHours").value(696))
            .andExpect(jsonPath("$.result.periodElectricityCost").value("835.200000"))
            .andReturn();
    var values = json.readTree(result.getResponse().getContentAsString()).path("result");
    double expectedCapital =
        400000 * .1 / (1 - Math.pow(1.1, -5)) + 140000 * .1 / (1 - Math.pow(1.1, -14));
    org.junit.jupiter.api.Assertions.assertEquals(
        expectedCapital,
        values.path("appliedAssumptions").path("annualCapitalRecoveryUsd").asDouble(),
        .000001);
    org.junit.jupiter.api.Assertions.assertEquals(
        (expectedCapital + 34000) / 12 + 835.2, values.path("periodCost").asDouble(), .000001);
  }

  private ObjectNode assetExample() throws Exception {
    var input = example();
    ((ObjectNode) input.path("assumptions")).put("costProfile", "SCREEN_SCENARIO");
    ((ObjectNode) input.path("items").get(0))
        .put("usefulLifeYears", 5)
        .put("residualValueUsd", "0");
    ((ArrayNode) input.path("items"))
        .addObject()
        .put("itemType", "RACK_FACILITY")
        .put("status", "USER_INPUT")
        .put("amount", "140000")
        .put("currency", "USD")
        .put("period", "ONE_TIME")
        .put("usefulLifeYears", 14)
        .put("residualValueUsd", "0");
    return input;
  }

  private ResultActions submit(ObjectNode input) throws Exception {
    return mvc.perform(
        post("/api/v1/cost-simulations")
            .contentType("application/json")
            .content(json.writeValueAsBytes(input)));
  }

  private ObjectNode example() throws Exception {
    return (ObjectNode)
        json.readTree(
            """
{"modelId":"2","items":[
   {"itemType":"ACQUISITION","status":"USER_INPUT","amount":"400000","currency":"USD","period":"ONE_TIME"},
   {"itemType":"OPERATIONS","status":"USER_INPUT","amount":"34000","currency":"USD","period":"YEAR"},
   {"itemType":"MEMORY","status":"INCLUDED_IN_OTHER","includedInItemType":"ACQUISITION"}
 ],"assumptions":{"gpuCount":8,"depreciationYears":5,"utilization":"0.70","interestRateType":"NOT_APPLIED"},
 "pricing":{"method":"REVENUE_MARGIN","targetRate":"0.20"}}
""");
  }
}
