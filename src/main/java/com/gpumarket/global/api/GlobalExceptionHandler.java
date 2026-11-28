package com.gpumarket.global.api;

import com.gpumarket.global.api.code.BaseErrorCode;
import com.gpumarket.global.api.code.status.ErrorStatus;
import com.gpumarket.global.api.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 오류를 {isSuccess:false, code, message}로 내려준다. 상태 코드만 던지지 않는다.
 *
 * <p>예상한 오류는 {@link GeneralException}({@link ErrorStatus})로, 예상하지 못한 오류는 내부 내용을 감추고 일반 문구로.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static ResponseEntity<ApiResponse<Void>> respond(BaseErrorCode code, String message) {
    return ResponseEntity.status(code.getHttpStatus()).body(ApiResponse.failure(code, message));
  }

  private static ResponseEntity<ApiResponse<Void>> respond(BaseErrorCode code) {
    return respond(code, null);
  }

  @ExceptionHandler(GeneralException.class)
  ResponseEntity<ApiResponse<Void>> general(GeneralException ex) {
    return respond(ex.getCode(), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiResponse<Void>> invalidFields(MethodArgumentNotValidException ex) {
    var errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new ApiResponse.ErrorDetail(e.getField(), "입력값이 유효하지 않습니다."))
            .distinct()
            .toList();
    return ResponseEntity.unprocessableEntity().body(ApiResponse.validationFailure(errors));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException ex) {
    // 요청 DTO가 직접 던진 한국어 안내("지원하지 않는 입력 항목입니다." 등)는 살리고, 그 밖의 파싱 오류는 일반 문구
    for (Throwable t = ex; t != null && t.getCause() != t; t = t.getCause()) {
      if (t instanceof IllegalArgumentException && isUserMessage(t.getMessage())) {
        return respond(ErrorStatus.INVALID_REQUEST, t.getMessage());
      }
    }
    return respond(ErrorStatus.INVALID_REQUEST);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ApiResponse<Void>> missingParameter(MissingServletRequestParameterException ex) {
    return respond(
        ErrorStatus.MISSING_PARAMETER, "필수 요청값 '" + ex.getParameterName() + "'이(가) 빠졌습니다.");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiResponse<Void>> typeMismatch(MethodArgumentTypeMismatchException ex) {
    return respond(ErrorStatus.INVALID_REQUEST, "'" + ex.getName() + "' 값의 형식이 올바르지 않습니다.");
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  ResponseEntity<ApiResponse<Void>> noRoute(Exception ex) {
    return respond(ErrorStatus.API_NOT_FOUND);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
    return respond(ErrorStatus.METHOD_NOT_ALLOWED);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiResponse<Void>> mediaType(HttpMediaTypeNotSupportedException ex) {
    return respond(ErrorStatus.UNSUPPORTED_MEDIA_TYPE);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException ex) {
    return respond(ErrorStatus.PAYLOAD_TOO_LARGE);
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ApiResponse<Void>> responseStatus(ResponseStatusException ex) {
    return respond(ErrorStatus.ofStatus(ex.getStatusCode().value()), ex.getReason());
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiResponse<Void>> databaseUnavailable(DataAccessException ex) {
    log.warn("DB 조회 실패: {}", ex.getClass().getSimpleName());
    return respond(ErrorStatus.DATA_UNAVAILABLE);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
    log.error("처리하지 못한 오류", ex); // 내용은 로그에만, 응답은 일반 문구
    return respond(ErrorStatus._INTERNAL_SERVER_ERROR);
  }

  /** 사용자에게 보여 줄 문구인가 — 한글이 있으면 우리가 쓴 안내로 본다 (자바·Jackson 내부 메시지는 감춘다). */
  private static boolean isUserMessage(String message) {
    return message != null && message.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
  }
}
