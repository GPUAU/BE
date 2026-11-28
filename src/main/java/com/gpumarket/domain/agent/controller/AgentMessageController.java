package com.gpumarket.domain.agent.controller;

import com.gpumarket.domain.agent.dto.AgentMessageRequestDto;
import com.gpumarket.domain.agent.dto.AgentMessageResponseDto;
import com.gpumarket.domain.agent.service.AgentMessageService;
import com.gpumarket.global.api.ApiResponse;
import com.gpumarket.global.api.code.status.ErrorStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
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

  @Operation(summary = "임시 공개 가격 질의 세션 삭제")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "204",
      description = "삭제 완료 또는 이미 종료된 세션")
  @DeleteMapping("/api/v1/agent-sessions/{sessionId}")
  public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
    service.deleteSession(sessionId);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(AgentMessageService.SensitiveInput.class)
  public ResponseEntity<ApiResponse<Void>> sensitive() {
    return ResponseEntity.status(ErrorStatus.SENSITIVE_INPUT_BLOCKED.getHttpStatus())
        .body(ApiResponse.failure(ErrorStatus.SENSITIVE_INPUT_BLOCKED));
  }
}
