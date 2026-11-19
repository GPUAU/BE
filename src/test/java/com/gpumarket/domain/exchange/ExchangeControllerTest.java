package com.gpumarket.domain.exchange;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gpumarket.domain.exchange.controller.ExchangeController;
import com.gpumarket.domain.exchange.service.ExchangeService;
import com.gpumarket.global.api.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExchangeControllerTest {
  private final ExchangeService service = mock(ExchangeService.class);
  private final org.springframework.test.web.servlet.MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ExchangeController(service))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void rejectsUnsupportedCurrency() throws Exception {
    mvc.perform(get("/api/v1/exchange-rates/latest").param("quoteCurrency", "EUR"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void rejectsZeroRateAndEmptyItems() throws Exception {
    mvc.perform(
            post("/api/v1/exchange-rate-simulations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[],\"priceType\":\"NOMINAL\",\"krwPerUsd\":\"0\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void rejectsMalformedJson() throws Exception {
    mvc.perform(
            post("/api/v1/exchange-rate-simulations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }
}
