package com.gpumarket.global.api.code;

import org.springframework.http.HttpStatus;

/** 오류 코드 공통 규약: HTTP 상태 + 코드 + 사람이 읽을 문구. */
public interface BaseErrorCode {
  HttpStatus getHttpStatus();

  String getCode();

  String getMessage();

  default ErrorReasonDTO getReasonHttpStatus() {
    return new ErrorReasonDTO(getHttpStatus(), false, getCode(), getMessage());
  }
}
