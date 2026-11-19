package com.gpumarket.domain.agent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentMessageRequestDto(
    @Size(max = 100) String sessionId,
    @NotBlank @Size(max = 500) String message,
    @Valid PublicContextDto publicContext) {
  @JsonAnySetter
  void unsupported(String ignored, JsonNode value) {
    throw new IllegalArgumentException("지원하지 않는 입력 항목입니다.");
  }
}
