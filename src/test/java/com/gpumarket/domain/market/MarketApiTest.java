package com.gpumarket.domain.market;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:market",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MarketApiTest {
  @Autowired MockMvc mvc;

  @Test
  void swaggerDocumentsHistoryParameters() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/market/price-histories'].get.parameters[?(@.name =="
                        + " 'modelId')]")
                .isNotEmpty());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void historyRequiresPeriodAndModel() throws Exception {
    mvc.perform(get("/api/v1/market/price-histories")).andExpect(status().isUnprocessableEntity());
  }

  @Test
  void summaryRequiresModelAndRejectsRegionMixing() throws Exception {
    mvc.perform(get("/api/v1/market/summaries")).andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/market/summaries?modelId=1&comparisonType=EXCLUDING_REGION"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void offerListRejectsInvalidLimit() throws Exception {
    mvc.perform(get("/api/v1/market/offers?limit=101")).andExpect(status().isUnprocessableEntity());
  }

  @Test
  void detailRejectsUnsupportedCurrency() throws Exception {
    mvc.perform(get("/api/v1/market/observations/1?currency=EUR"))
        .andExpect(status().isUnprocessableEntity());
  }
}
