package com.gpumarket.domain.catalog;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gpumarket.domain.catalog.controller.CatalogController;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto;
import com.gpumarket.domain.catalog.service.CatalogService;
import com.gpumarket.domain.exchange.controller.ExchangeController;
import com.gpumarket.domain.exchange.service.ExchangeService;
import com.gpumarket.global.LocalSecurityConfig;
import com.gpumarket.global.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({CatalogController.class, ExchangeController.class})
@Import({SecurityConfig.class, LocalSecurityConfig.class})
@ActiveProfiles("local")
class LocalSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean CatalogService catalog;
  @MockBean ExchangeService exchange;

  @Test
  void permitsCatalogAndKeepsOtherPathsDenied() throws Exception {
    when(catalog.getCatalog())
        .thenReturn(
            new CatalogResponseDto(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));
    mvc.perform(get("/api/v1/catalog")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/unimplemented")).andExpect(status().isForbidden());
  }

  @Test
  void postReachesValidationWithoutCsrfToken() throws Exception {
    mvc.perform(
            post("/api/v1/exchange-rate-simulations")
                .contentType("application/json")
                .content("{\"items\":[],\"priceType\":\"NOMINAL\",\"krwPerUsd\":0}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void unlistedMethodsRemainDenied() throws Exception {
    mvc.perform(delete("/api/v1/catalog")).andExpect(status().isForbidden());
  }
}
