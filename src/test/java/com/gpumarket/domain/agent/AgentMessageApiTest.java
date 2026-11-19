package com.gpumarket.domain.agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
      "spring.datasource.url=jdbc:h2:mem:agent-messages",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AgentMessageApiTest {
  @Autowired MockMvc mvc;

  @Test
  void rejectsSensitiveInputBeforeLookingUpPublicData() throws Exception {
    mvc.perform(
            post("/api/v1/agent-messages")
                .contentType("application/json")
                .content("{\"message\":\"B300 내부 원가 알려줘\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SENSITIVE_INPUT_BLOCKED"));
  }

  @Test
  void validatesBlankMessagesAndRejectsUnknownContextFields() throws Exception {
    mvc.perform(
            post("/api/v1/agent-messages")
                .contentType("application/json")
                .content("{\"message\":\" \"}"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            post("/api/v1/agent-messages")
                .contentType("application/json")
                .content("{\"message\":\"B300 가격\",\"publicContext\":{\"internalCost\":100}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void reportsUnavailablePriceDataAsDependencyFailure() throws Exception {
    mvc.perform(
            post("/api/v1/agent-messages")
                .contentType("application/json")
                .content("{\"message\":\"B300 온디맨드 가격 알려줘\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("DATA_UNAVAILABLE"));
  }
}
