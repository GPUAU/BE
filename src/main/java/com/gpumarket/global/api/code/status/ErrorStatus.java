package com.gpumarket.global.api.code.status;

import com.gpumarket.global.api.code.BaseErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 오류 목록. 상태 코드만 던지지 않고, 화면에 그대로 보여 줄 수 있는 문구를 함께 둔다.
 *
 * <p>이미 화면·테스트가 쓰는 코드(INVALID_REQUEST, VALIDATION_ERROR, NOT_FOUND, CURSOR_EXPIRED,
 * DATA_UNAVAILABLE, SENSITIVE_INPUT_BLOCKED)는 값을 바꾸지 않는다.
 */
public enum ErrorStatus implements BaseErrorCode {
  // 가장 일반적인 응답
  _BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400", "잘못된 요청입니다."),
  _UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON401", "인증이 필요합니다."),
  _FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON403", "권한이 없습니다."),
  _NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 대상을 찾을 수 없습니다."),
  _INTERNAL_SERVER_ERROR(
      HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500", "일시적인 서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

  // 요청 형식
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식을 확인해 주세요."),
  MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "필수 요청값이 빠졌습니다."),
  VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "요청값을 확인해 주세요."),
  API_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON404", "존재하지 않는 API 주소입니다."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON405", "지원하지 않는 요청 방식입니다."),
  CONFLICT(HttpStatus.CONFLICT, "COMMON409", "요청이 현재 상태와 맞지 않습니다. 새로고침한 뒤 다시 시도해 주세요."),
  PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "COMMON413", "요청 크기가 너무 큽니다."),
  UNSUPPORTED_MEDIA_TYPE(
      HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON415", "지원하지 않는 요청 형식입니다. JSON으로 보내 주세요."),
  TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "COMMON429", "요청이 많습니다. 잠시 후 다시 시도해 주세요."),
  SERVICE_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "COMMON503", "서비스를 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),

  // 데이터
  DATA_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "DATA_UNAVAILABLE", "데이터를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요."),
  CURSOR_EXPIRED(HttpStatus.GONE, "CURSOR_EXPIRED", "커서가 만료되었습니다. 첫 페이지부터 조회하세요."),

  // 시장가격
  OBSERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "MARKET4041", "해당 가격 관측 기록을 찾을 수 없습니다."),
  OFFER_NOT_FOUND(HttpStatus.NOT_FOUND, "MARKET4042", "해당 가격 오퍼를 찾을 수 없습니다."),
  PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "MARKET4043", "해당 상품을 찾을 수 없습니다."),

  // 에이전트
  AGENT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "AGENT4041", "대화 세션이 없거나 만료되었습니다. 새로 시작해 주세요."),
  SENSITIVE_INPUT_BLOCKED(
      HttpStatus.UNPROCESSABLE_ENTITY, "SENSITIVE_INPUT_BLOCKED", "공개 시장가격 조회에 사용할 조건만 입력해 주세요.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;

  ErrorStatus(HttpStatus httpStatus, String code, String message) {
    this.httpStatus = httpStatus;
    this.code = code;
    this.message = message;
  }

  @Override
  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  @Override
  public String getCode() {
    return code;
  }

  @Override
  public String getMessage() {
    return message;
  }

  /** 상태 코드만 있는 예외(ResponseStatusException 등)를 가장 가까운 오류로. */
  public static ErrorStatus ofStatus(int status) {
    return switch (status) {
      case 400 -> INVALID_REQUEST;
      case 401 -> _UNAUTHORIZED;
      case 403 -> _FORBIDDEN;
      case 404 -> _NOT_FOUND;
      case 405 -> METHOD_NOT_ALLOWED;
      case 409 -> CONFLICT;
      case 410 -> CURSOR_EXPIRED;
      case 413 -> PAYLOAD_TOO_LARGE;
      case 415 -> UNSUPPORTED_MEDIA_TYPE;
      case 422 -> VALIDATION_ERROR;
      case 429 -> TOO_MANY_REQUESTS;
      case 503 -> SERVICE_UNAVAILABLE;
      default -> status >= 500 ? _INTERNAL_SERVER_ERROR : _BAD_REQUEST;
    };
  }
}
