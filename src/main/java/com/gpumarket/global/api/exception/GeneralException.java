package com.gpumarket.global.api.exception;

import com.gpumarket.global.api.code.BaseErrorCode;

/**
 * 예상한 오류. {@code throw new GeneralException(ErrorStatus.AGENT_SESSION_NOT_FOUND)}처럼 던지면 전역 처리기가
 * 상태·코드·문구로 응답한다. 같은 코드에 상황별 문구가 필요하면 message를 함께 준다.
 */
public class GeneralException extends RuntimeException {
  private final BaseErrorCode code;

  public GeneralException(BaseErrorCode code) {
    this(code, code.getMessage());
  }

  public GeneralException(BaseErrorCode code, String message) {
    super(message == null || message.isBlank() ? code.getMessage() : message);
    this.code = code;
  }

  public BaseErrorCode getCode() {
    return code;
  }
}
