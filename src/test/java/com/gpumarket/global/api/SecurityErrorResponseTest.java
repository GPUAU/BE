package com.gpumarket.global.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/** 보안 설정이 막은 요청도 빈 403이 아니라 같은 모양의 읽을 수 있는 오류로 내려간다. */
@WebMvcTest(CatalogController.class)
@Import({SecurityConfig.class, LocalSecurityConfig.class, DeploySecurityConfig.class})
@ActiveProfiles("deploy")
class SecurityErrorResponseTest {
  @Autowired MockMvc mvc;
  @MockBean CatalogService service;

  @Test
  void blockedRequestIsReadable() throws Exception {
    mvc.perform(post("/api/v1/catalog"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value("COMMON403"))
        .andExpect(jsonPath("$.message").value("권한이 없습니다."));
    mvc.perform(get("/swagger-ui.html")).andExpect(jsonPath("$.code").value("COMMON403"));
  }
}
