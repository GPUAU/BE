package com.gpumarket.global.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gpumarket.global.api.code.status.ErrorStatus;
import com.gpumarket.global.api.exception.GeneralException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 오류는 상태 코드만이 아니라 {isSuccess:false, code, message}로 사람이 읽을 수 있게 내려간다. */
class GlobalExceptionHandlerTest {
  private MockMvc mvc;

  record Body(String kind) {
    Body {
      if (!"ok".equals(kind)) throw new IllegalArgumentException("지원하지 않는 입력 항목입니다.");
    }
  }

  @RestController
  static class Probe {
    @GetMapping("/forbidden")
    void forbidden() {
      throw new GeneralException(ErrorStatus._FORBIDDEN);
    }

    @GetMapping("/session")
    void session() {
      throw new GeneralException(ErrorStatus.AGENT_SESSION_NOT_FOUND);
    }

    @GetMapping("/custom")
    void custom() {
      throw new GeneralException(ErrorStatus.INVALID_REQUEST, "조회 기간은 최대 90일입니다.");
    }

    @GetMapping("/boom")
    void boom() {
      throw new IllegalStateException("db password=secret");
    }

    @GetMapping("/conflict")
    void conflict() {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    @GetMapping("/bare-404")
    void bare404() {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/no-route")
    void noRoute() throws Exception {
      throw new NoResourceFoundException(HttpMethod.GET, "api/v1/nope");
    }

    @GetMapping("/param")
    void param(@RequestParam String modelId) {}

    @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    void json(@RequestBody Body body) {}
  }

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private ResultActions failure(ResultActions r, int status, String code) throws Exception {
    return r.andExpect(status().is(status))
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.meta.requestId").isString());
  }

  @Test
  void generalExceptionCarriesStatusCodeAndMessage() throws Exception {
    failure(mvc.perform(get("/forbidden")), 403, "COMMON403")
        .andExpect(jsonPath("$.message").value("권한이 없습니다."));
    failure(mvc.perform(get("/session")), 404, "AGENT4041")
        .andExpect(jsonPath("$.message").value(Matchers.containsString("새로 시작")));
  }

  @Test
  void generalExceptionCanOverrideMessage() throws Exception {
    failure(mvc.perform(get("/custom")), 400, "INVALID_REQUEST")
        .andExpect(jsonPath("$.message").value("조회 기간은 최대 90일입니다."));
  }

  @Test
  void unexpectedErrorIsReadableAndHidesInternals() throws Exception {
    failure(mvc.perform(get("/boom")), 500, "COMMON500")
        .andExpect(jsonPath("$.message").value(Matchers.containsString("잠시 후 다시")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("secret"))));
  }

  @Test
  void responseStatusIsMappedByStatusNotAlwaysNotFound() throws Exception {
    failure(mvc.perform(get("/conflict")), 409, "COMMON409");
    failure(mvc.perform(get("/bare-404")), 404, "NOT_FOUND")
        .andExpect(jsonPath("$.message").value("요청한 대상을 찾을 수 없습니다."));
  }

  @Test
  void unknownApiPath() throws Exception {
    failure(mvc.perform(get("/no-route")), 404, "COMMON404")
        .andExpect(jsonPath("$.message").value("존재하지 않는 API 주소입니다."));
  }

  @Test
  void wrongMethodAndMediaType() throws Exception {
    failure(mvc.perform(post("/forbidden")), 405, "COMMON405");
    failure(
        mvc.perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("x")),
        415,
        "COMMON415");
  }

  @Test
  void missingParameterNamesTheParameter() throws Exception {
    failure(mvc.perform(get("/param")), 400, "INVALID_REQUEST")
        .andExpect(jsonPath("$.message").value(Matchers.containsString("modelId")));
  }

  @Test
  void dtoValidationMessageIsKept() throws Exception {
    failure(
            mvc.perform(
                post("/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kind\":\"bad\"}")),
            400,
            "INVALID_REQUEST")
        .andExpect(jsonPath("$.message").value("지원하지 않는 입력 항목입니다."));
    failure(
            mvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{broken")),
            400,
            "INVALID_REQUEST")
        .andExpect(jsonPath("$.message").value("요청 형식을 확인해 주세요."));
  }
}
