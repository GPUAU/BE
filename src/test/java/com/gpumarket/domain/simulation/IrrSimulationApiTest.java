package com.gpumarket.domain.simulation;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gpumarket.domain.catalog.repository.CatalogRepository;
import com.gpumarket.domain.market.service.MarketOfferService;
import com.gpumarket.domain.market.service.MarketOfferService.ComparableGroup;
import com.gpumarket.domain.market.service.MarketOfferService.ComparableOffer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
      "spring.datasource.url=jdbc:h2:mem:irr-simulations",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class IrrSimulationApiTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockBean CatalogRepository catalog;
  @MockBean MarketOfferService market;

  @BeforeEach
  void knownModel() {
    when(catalog.modelExists(2L)).thenReturn(true);
  }

  @Test
  void modelsCashFlowsAndSolvesTheLowestPriceThatMeetsTheGoal() throws Exception {
    submit(example())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.resultStatus").value("OK"))
        .andExpect(jsonPath("$.result.assumptions.investmentBasis").value("ACQUISITION_DEFAULT"))
        .andExpect(jsonPath("$.result.assumptions.analysisYears").value(1))
        .andExpect(jsonPath("$.result.assumptions.financingIncludedInCashFlow").value(false))
        .andExpect(
            jsonPath("$.result.candidateResults[0].cashFlows[0].amount").value("-100000.000000"))
        .andExpect(
            jsonPath("$.result.candidateResults[0].cashFlows[1].amount").value("110000.000000"))
        .andExpect(jsonPath("$.result.candidateResults[0].irr").value("0.100000000000"))
        .andExpect(jsonPath("$.result.candidateResults[1].status").value("IRR_UNAVAILABLE"))
        .andExpect(jsonPath("$.result.candidateResults[2].irr").value("0.100000000000"))
        .andExpect(jsonPath("$.result.targetPrice.amount").value("15.000000000000"))
        .andExpect(jsonPath("$.result.targetPrice.irr").value("0.100000000000"))
        .andExpect(jsonPath("$.result.marketReference.status").value("NOT_REQUESTED"))
        .andExpect(jsonPath("$.result.saved").value(false));
    verifyNoInteractions(market);
  }

  @Test
  void distinguishesMissingMarketReferenceFromInsufficientFilters() throws Exception {
    var request = example().put("marketConstraint", "MEDIAN");
    submit(request)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("MARKET_CONDITIONS_UNSPECIFIED"));
    request.set("marketFilters", marketFilters());
    when(market.comparablePrices(anyMap(), eq(true))).thenReturn(List.of());
    submit(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.resultStatus").value("MARKET_REFERENCE_UNAVAILABLE"))
        .andExpect(jsonPath("$.result.targetPrice.status").value("NOT_EVALUATED"))
        .andExpect(jsonPath("$.result.candidateResults[0].irr").value("0.100000000000"));
  }

  @Test
  void rejectsAnUnrequestedMarketLookupAndOutOfRangeTarget() throws Exception {
    submit(example().set("marketFilters", marketFilters()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("marketFilters"));
    submit(example().put("targetIrr", "-1"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("targetIrr"));
    submit(example().put("marketConstraint", 0))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(market);
  }

  @Test
  void doesNotCombineSeparateMarketComparisonGroups() throws Exception {
    var request = example().put("marketConstraint", "MEDIAN");
    request.set("marketFilters", marketFilters());
    when(market.comparablePrices(anyMap(), eq(true)))
        .thenReturn(
            List.of(
                new ComparableGroup(Map.of("regionCode", "a"), Map.of(), List.of(), Instant.EPOCH),
                new ComparableGroup(
                    Map.of("regionCode", "b"), Map.of(), List.of(), Instant.EPOCH)));
    submit(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.reason").value("MULTIPLE_COMPARISON_GROUPS"))
        .andExpect(jsonPath("$.result.marketScenarios").isEmpty());
  }

  @Test
  void keepsTheMarketMedianAtOneRepresentativePerProviderAndReturnsTheCapIrr() throws Exception {
    var request = example().put("marketConstraint", "MEDIAN");
    request.set("marketFilters", marketFilters());
    when(market.comparablePrices(anyMap(), eq(true)))
        .thenReturn(
            List.of(
                new ComparableGroup(
                    Map.of("regionCode", "region-a"),
                    Map.of(
                        "providerCount",
                        2,
                        "productCount",
                        3,
                        "offerCount",
                        3,
                        "observationCount",
                        3),
                    List.of(offer("1", 1, "12"), offer("2", 1, "30"), offer("3", 2, "16")),
                    Instant.EPOCH)));

    submit(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.resultStatus").value("NO_FEASIBLE_PRICE"))
        .andExpect(jsonPath("$.result.marketReference.amount").value("14"))
        .andExpect(jsonPath("$.result.marketReference.sampleCounts.providerCount").value(2))
        .andExpect(jsonPath("$.result.marketReference.sources[0].sourceUrl").exists())
        .andExpect(jsonPath("$.result.targetPrice.amount").isEmpty())
        .andExpect(jsonPath("$.result.marketScenarios[1].irr").value("0.012400000000"))
        .andExpect(
            jsonPath("$.result.marketScenarios[1].reason").value("TARGET_PRICE_ABOVE_MARKET_CAP"));

    request.put("marketConstraint", "MAXIMUM");
    submit(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.resultStatus").value("OK"))
        .andExpect(jsonPath("$.result.targetPrice.amount").value("15.000000000000"));
  }

  @Test
  void preservesSmallCashFlowsAndAcceptsZeroWhenItMeetsTheTarget() throws Exception {
    var request = example().put("investment", "0.00000001").put("residualValue", "0.00000002");
    ((ObjectNode) request.path("costInput").path("items").get(1)).put("amount", "0");
    request.putArray("candidatePrices").add("0");

    submit(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.assumptions.investmentDiffersFromAcquisition").value(true))
        .andExpect(
            jsonPath("$.result.candidateResults[0].cashFlows[0].amount").value("-0.00000001"))
        .andExpect(jsonPath("$.result.candidateResults[0].cashFlows[1].amount").value("0.00000002"))
        .andExpect(jsonPath("$.result.targetPrice.amount").value("0.000000000000"))
        .andExpect(jsonPath("$.result.targetPrice.irr").value("1.000000000000"));
  }

  @Test
  void usesExplicitInvestmentWhenAcquisitionIsMissingAndValidatesTheCostBasis() throws Exception {
    var request = example();
    var items = (ArrayNode) request.path("costInput").path("items");
    items.remove(0);

    submit(request)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("investment"));
    submit(request.put("investment", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.assumptions.investmentBasis").value("USER_INPUT"))
        .andExpect(jsonPath("$.result.costCompleteness").value("INCOMPLETE"));

    items.removeAll().addObject().put("itemType", "OPERATIONS").put("status", "NOT_INCLUDED");
    submit(request)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("costInput.items"));
  }

  @Test
  void returnsTheConstraintReasonEvenWhenTheCapHasNoIrr() throws Exception {
    var request = example().put("marketConstraint", "MEDIAN");
    request.set("marketFilters", marketFilters());
    ((ObjectNode) request.path("costInput").path("items").get(1)).put("amount", "1000000");
    when(market.comparablePrices(anyMap(), eq(true)))
        .thenReturn(
            List.of(
                new ComparableGroup(
                    Map.of("regionCode", "region-a"),
                    Map.of(
                        "providerCount",
                        1,
                        "productCount",
                        1,
                        "offerCount",
                        1,
                        "observationCount",
                        1),
                    List.of(offer("1", 1, "12")),
                    Instant.EPOCH)));

    submit(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.resultStatus").value("NO_FEASIBLE_PRICE"))
        .andExpect(jsonPath("$.result.marketScenarios[1].status").value("IRR_UNAVAILABLE"))
        .andExpect(jsonPath("$.result.marketScenarios[1].irr").isEmpty())
        .andExpect(
            jsonPath("$.result.marketScenarios[1].reason").value("TARGET_PRICE_ABOVE_MARKET_CAP"));
  }

  @Test
  void preservesIrrPrecisionForLargeCashFlowsAndSmallInvestments() throws Exception {
    var request = example().put("investment", "0.00000001").put("residualValue", "0");
    var cost = (ObjectNode) request.path("costInput");
    ((ObjectNode) cost.path("assumptions")).put("gpuCount", Integer.MAX_VALUE);
    ((ObjectNode) cost.path("items").get(1)).put("amount", "0");
    request.putArray("candidatePrices").add("9999999999999999.999999999999");

    submit(request)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.result.candidateResults[0].irr")
                .value("18811956747719999999999999998118804324.228000000000"));
  }

  private ComparableOffer offer(String id, long providerId, String price) {
    return new ComparableOffer(
        id,
        providerId,
        new BigDecimal(price),
        Map.of(),
        Instant.EPOCH,
        List.of(Map.of("evidenceId", id, "sourceUrl", "https://example.org/pricing/" + id)));
  }

  private ResultActions submit(ObjectNode input) throws Exception {
    return mvc.perform(
        post("/api/v1/irr-simulations")
            .contentType("application/json")
            .content(json.writeValueAsBytes(input)));
  }

  private ObjectNode example() throws Exception {
    return (ObjectNode)
        json.readTree(
            """
{
  "costInput": {
    "modelId": "2",
    "items": [
      {"itemType":"ACQUISITION","status":"USER_INPUT","amount":"100000","currency":"USD","period":"ONE_TIME"},
      {"itemType":"OPERATIONS","status":"USER_INPUT","amount":"31400","currency":"USD","period":"YEAR"}
    ],
    "assumptions": {"gpuCount":1,"depreciationYears":1,"utilization":"1","interestRateType":"NOT_APPLIED"}
  },
  "residualValue": "10000",
  "targetIrr": "0.10",
  "candidatePrices": ["15", "0", "15"],
  "marketConstraint": "NONE"
}
""");
  }

  private ObjectNode marketFilters() throws Exception {
    return (ObjectNode)
        json.readTree(
            """
            {"providerType":"GPU_SPECIALIST","countryCode":"US","systemType":"INSTANCE",
             "serviceOption":"STANDARD","gpuCount":1,"contractType":"ON_DEMAND",
             "contractProductType":"ON_DEMAND","paymentOption":"NOT_APPLICABLE",
             "intraNodeInterconnect":"NVLINK"}
            """);
  }
}
