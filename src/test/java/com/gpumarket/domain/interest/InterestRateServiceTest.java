package com.gpumarket.domain.interest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gpumarket.domain.interest.controller.InterestRateController;
import com.gpumarket.domain.interest.service.EcosRateParser;
import com.gpumarket.domain.interest.service.InterestRateService;
import com.gpumarket.global.api.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 한국은행 ECOS 회사채(3년, AA-) 금리를 원가 계산의 금리 가정 제안값으로 내려준다. */
class InterestRateServiceTest {
  static String body(String... rows) {
    var items = new ArrayList<String>();
    for (String r : rows) {
      String[] p = r.split("=", -1);
      items.add(
          "{\"STAT_CODE\":\"817Y002\",\"ITEM_CODE1\":\"010300000\",\"ITEM_NAME1\":\"회사채(3년,"
              + " AA-)\",\"UNIT_NAME\":\"연%\",\"TIME\":\""
              + p[0]
              + "\",\"DATA_VALUE\":\""
              + p[1]
              + "\"}");
    }
    return "{\"StatisticSearch\":{\"list_total_count\":"
        + rows.length
        + ",\"row\":["
        + String.join(",", items)
        + "]}}";
  }

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-28T03:00:00Z"), ZoneOffset.UTC);

  @Test
  void parserTakesTheLatestDayWithAValue() {
    var rate = EcosRateParser.parseLatest(body("20261126=4.380", "20261127=4.416", "20261128="));
    assertThat(rate).isPresent();
    assertThat(rate.get().date()).isEqualTo(LocalDate.of(2026, 11, 27));
    assertThat(rate.get().percent()).isEqualByComparingTo("4.416");
  }

  @Test
  void parserTreatsNoDataAsEmptyAndErrorsAsFailures() {
    assertThat(
            EcosRateParser.parseLatest(
                "{\"RESULT\":{\"CODE\":\"INFO-200\",\"MESSAGE\":\"해당하는 데이터가 없습니다.\"}}"))
        .isEmpty();
    assertThatThrownBy(
            () ->
                EcosRateParser.parseLatest(
                    "{\"RESULT\":{\"CODE\":\"INFO-100\",\"MESSAGE\":\"인증키가 유효하지 않습니다.\"}}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EcosRateParser.parseLatest("<html>"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void latestIsAvailableWithSourceAndDecimalRate() {
    List<URI> calls = new ArrayList<>();
    var service =
        new InterestRateService(
            uri -> {
              calls.add(uri);
              return body("20261127=4.416");
            },
            "KEY",
            CLOCK);
    var dto = service.latest();
    assertThat(dto.status()).isEqualTo("AVAILABLE");
    assertThat(dto.annualRatePercent()).isEqualTo("4.416");
    assertThat(dto.annualRate()).isEqualByComparingTo(new BigDecimal("0.04416"));
    assertThat(dto.referenceDate()).isEqualTo(LocalDate.of(2026, 11, 27));
    assertThat(dto.seriesName()).isEqualTo("회사채(3년, AA-)");
    assertThat(dto.source().name()).contains("한국은행");
    assertThat(calls.get(0).toString())
        .contains("/StatisticSearch/KEY/json/kr/1/31/817Y002/D/20261029/20261128/010300000");
  }

  @Test
  void resultIsCachedSoEcosIsNotCalledOnEveryRequest() {
    List<URI> calls = new ArrayList<>();
    var service =
        new InterestRateService(
            uri -> {
              calls.add(uri);
              return body("20261127=4.416");
            },
            "KEY",
            CLOCK);
    service.latest();
    service.latest();
    assertThat(calls).hasSize(1);
  }

  @Test
  void missingKeyOrFailureIsNotAvailableWithAReadableWarning() {
    var noKey = new InterestRateService(uri -> body("20261127=4.416"), "", CLOCK).latest();
    assertThat(noKey.status()).isEqualTo("NOT_AVAILABLE");
    assertThat(noKey.warnings()).anyMatch(w -> w.contains("API 키"));
    var broken =
        new InterestRateService(
                uri -> {
                  throw new RuntimeException("timeout");
                },
                "KEY",
                CLOCK)
            .latest();
    assertThat(broken.status()).isEqualTo("NOT_AVAILABLE");
    assertThat(broken.warnings()).anyMatch(w -> w.contains("가져오지 못했습니다"));
    assertThat(broken.annualRate()).isNull();
  }

  @Test
  void endpointReturnsTheCommonEnvelope() throws Exception {
    var service = new InterestRateService(uri -> body("20261127=4.416"), "KEY", CLOCK);
    var mvc =
        MockMvcBuilders.standaloneSetup(new InterestRateController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mvc.perform(get("/api/v1/interest-rates/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.result.status").value("AVAILABLE"))
        .andExpect(jsonPath("$.result.annualRatePercent").value("4.416"))
        .andExpect(jsonPath("$.result.source.statCode").value("817Y002"));
  }
}
