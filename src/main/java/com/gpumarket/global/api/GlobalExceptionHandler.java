package com.gpumarket.global.api;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiResponse<Void>> invalidFields(MethodArgumentNotValidException ex) {
    var errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new ApiResponse.ErrorDetail(e.getField(), "입력값이 유효하지 않습니다."))
            .distinct()
            .toList();
    return ResponseEntity.unprocessableEntity().body(ApiResponse.validationFailure(errors));
  }

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<ApiResponse<Void>> invalidRequest(InvalidRequestException ex) {
    return ResponseEntity.unprocessableEntity()
        .body(ApiResponse.failure("VALIDATION_ERROR", ex.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ApiResponse<Void>> malformedRequest(Exception ex) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.failure("INVALID_REQUEST", "요청 형식을 확인해 주세요."));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ApiResponse<Void>> responseStatus(ResponseStatusException ex) {
    if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.failure("INVALID_REQUEST", ex.getReason()));
    }
    if (ex.getStatusCode() == HttpStatus.GONE) {
      return ResponseEntity.status(HttpStatus.GONE)
          .body(ApiResponse.failure("CURSOR_EXPIRED", "커서가 만료되었습니다. 첫 페이지부터 조회하세요."));
    }
    return ResponseEntity.status(ex.getStatusCode())
        .body(ApiResponse.failure("NOT_FOUND", "요청한 대상을 찾을 수 없습니다."));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiResponse<Void>> databaseUnavailable(DataAccessException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.failure("DATA_UNAVAILABLE", "데이터를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요."));
  }
}
