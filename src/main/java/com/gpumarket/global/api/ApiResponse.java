package com.gpumarket.global.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean isSuccess,
    String code,
    String message,
    T result,
    MetaDto meta,
    List<ErrorDetail> errors) {
  public record MetaDto(String requestId, Instant generatedAt) {}

  public record ErrorDetail(String field, String reason) {}

  private static MetaDto metadata() {
    return new MetaDto(UUID.randomUUID().toString(), Instant.now());
  }

  public static <T> ApiResponse<T> success(T result) {
    return new ApiResponse<>(true, "COMMON200", "성공!", result, metadata(), null);
  }

  public static ApiResponse<Void> failure(String code, String message) {
    return new ApiResponse<>(false, code, message, null, metadata(), null);
  }

  public static ApiResponse<Void> validationFailure(List<ErrorDetail> errors) {
    return new ApiResponse<>(false, "VALIDATION_ERROR", "요청값을 확인해 주세요.", null, metadata(), errors);
  }
}
