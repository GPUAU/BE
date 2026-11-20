package com.gpumarket.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
  @Autowired ObjectMapper json;

  @Test
  void deletesOnlyTheRequestedSessionAndReturnsNoBodyOnRepeatedCalls() throws Exception {
    String first = openSession();
    String second = openSession();
    assertThat(first).isNotEqualTo(second);

    mvc.perform(delete("/api/v1/agent-sessions/{sessionId}", first))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    mvc.perform(delete("/api/v1/agent-sessions/{sessionId}", first))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    mvc.perform(followUp(first))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(followUp(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.sessionId").value(second));
  }

  @Test
  void acceptsUnknownIdsForDeletionButRejectsInvalidIds() throws Exception {
    mvc.perform(delete("/api/v1/agent-sessions/{sessionId}", UUID.randomUUID()))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    mvc.perform(delete("/api/v1/agent-sessions/{sessionId}", "invalid.id"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

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

  private String openSession() throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/agent-messages")
                    .contentType("application/json")
                    .content("{\"message\":\"안녕하세요\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.sessionId").isString())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsByteArray())
        .path("result")
        .path("sessionId")
        .asText();
  }

  private MockHttpServletRequestBuilder followUp(String sessionId) {
    return post("/api/v1/agent-messages")
        .contentType("application/json")
        .content(
            json.createObjectNode().put("sessionId", sessionId).put("message", "안녕하세요").toString());
  }
}
