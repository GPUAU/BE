package com.gpumarket.global.api.code;

import org.springframework.http.HttpStatus;

/** 오류 응답을 만들 때 쓰는 값 묶음. */
public record ErrorReasonDTO(
    HttpStatus httpStatus, boolean isSuccess, String code, String message) {}
