package com.gpumarket.domain.catalog;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gpumarket.domain.catalog.controller.CatalogController;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto;
import com.gpumarket.domain.catalog.service.CatalogService;
import com.gpumarket.global.api.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogControllerTest {
  private CatalogService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(CatalogService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new CatalogController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void returnsCommonEnvelopeAndEmptyArrays() throws Exception {
    when(service.getCatalog())
        .thenReturn(
            new CatalogResponseDto(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));
    mvc.perform(get("/api/v1/catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.code").value("COMMON200"))
        .andExpect(jsonPath("$.result.providers").isArray())
        .andExpect(jsonPath("$.meta.requestId").isString());
  }

  @Test
  void databaseFailureDoesNotReturnRawDetails() throws Exception {
    when(service.getCatalog())
        .thenThrow(new DataAccessResourceFailureException("sensitive internal detail"));
    mvc.perform(get("/api/v1/catalog"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value("DATA_UNAVAILABLE"))
        .andExpect(jsonPath("$.result").doesNotExist())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive internal detail"))));
  }
}
