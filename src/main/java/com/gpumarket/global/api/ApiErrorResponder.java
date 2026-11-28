package com.gpumarket.global.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpumarket.global.api.code.BaseErrorCode;
import com.gpumarket.global.api.code.status.ErrorStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 보안 설정이 막은 요청(목록에 없는 경로·메서드)은 컨트롤러까지 오지 않아 전역 처리기가 못 잡는다. 빈 403 대신 같은 모양으로 쓴다.
 *
 * <p>아직 로그인 방식이 없어 막힌 요청은 모두 '권한이 없습니다.'(403)로 답한다.
 */
public class ApiErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final ObjectMapper mapper;

  public ApiErrorResponder(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
      throws IOException {
    write(res, ErrorStatus._FORBIDDEN);
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex)
      throws IOException {
    write(res, ErrorStatus._FORBIDDEN);
  }

  private void write(HttpServletResponse res, BaseErrorCode code) throws IOException {
    res.setStatus(code.getHttpStatus().value());
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
    mapper.writeValue(res.getOutputStream(), ApiResponse.failure(code));
  }
}
