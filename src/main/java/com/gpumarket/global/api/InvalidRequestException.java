package com.gpumarket.global.api;

import com.gpumarket.global.api.code.status.ErrorStatus;
import com.gpumarket.global.api.exception.GeneralException;

/** 요청값 오류 (422 VALIDATION_ERROR). 문구는 상황마다 넘긴다. GeneralException의 한 종류. */
public class InvalidRequestException extends GeneralException {
  public InvalidRequestException(String message) {
    super(ErrorStatus.VALIDATION_ERROR, message);
  }
}
