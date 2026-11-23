package com.gpumarket.domain.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gpumarket.domain.catalog.controller.CatalogController;
import com.gpumarket.domain.catalog.service.CatalogService;
import com.gpumarket.global.DeploySecurityConfig;
import com.gpumarket.global.LocalSecurityConfig;
import com.gpumarket.global.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CatalogController.class)
@Import({SecurityConfig.class, LocalSecurityConfig.class, DeploySecurityConfig.class})
@ActiveProfiles({"deploy", "prod"})
class ProductionPolicySecurityTest {
  @Autowired MockMvc mvc;
  @MockBean CatalogService service;

  @Test
  void prodKeepsFailClosedPolicyEvenWithDeploy() throws Exception {
    mvc.perform(get("/api/v1/catalog")).andExpect(status().isForbidden());
  }
}
