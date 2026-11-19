package com.gpumarket.domain.agent.controller;

import com.gpumarket.domain.agent.dto.AgentMessageRequestDto;
import com.gpumarket.domain.agent.dto.AgentMessageResponseDto;
import com.gpumarket.domain.agent.service.AgentMessageService;
import com.gpumarket.global.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentMessageController {
  private final AgentMessageService service;

  public AgentMessageController(AgentMessageService service) {
    this.service = service;
  }

  @Operation(summary = "공개 온디맨드 가격 질의")
  @PostMapping("/api/v1/agent-messages")
  public ApiResponse<AgentMessageResponseDto> message(
      @Valid @RequestBody AgentMessageRequestDto request) {
    return ApiResponse.success(service.answer(request));
  }

  @ExceptionHandler(AgentMessageService.SensitiveInput.class)
  public ResponseEntity<ApiResponse<Void>> sensitive() {
    return ResponseEntity.unprocessableEntity()
        .body(ApiResponse.failure("SENSITIVE_INPUT_BLOCKED", "공개 시장가격 조회에 사용할 조건만 입력해 주세요."));
  }
}
