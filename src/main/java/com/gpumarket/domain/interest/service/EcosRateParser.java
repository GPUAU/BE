package com.gpumarket.domain.interest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** ECOS StatisticSearch 응답에서 값이 있는 가장 최근 날의 금리를 고른다. */
public final class EcosRateParser {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public record EcosRate(LocalDate date, BigDecimal percent) {}

  private EcosRateParser() {}

  public static Optional<EcosRate> parseLatest(String body) {
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (Exception e) {
      throw new IllegalArgumentException("ECOS 응답을 읽을 수 없습니다.");
    }
    if (root == null || !root.isObject())
      throw new IllegalArgumentException("ECOS 응답 형식이 올바르지 않습니다.");
    JsonNode result = root.path("RESULT");
    if (!result.isMissingNode()) {
      if ("INFO-200".equals(result.path("CODE").asText())) return Optional.empty(); // 해당하는 데이터 없음
      throw new IllegalArgumentException("ECOS 요청 실패: " + result.path("CODE").asText());
    }
    EcosRate latest = null;
    for (JsonNode row : root.path("StatisticSearch").path("row")) {
      String value = row.path("DATA_VALUE").asText("").trim();
      if (value.isEmpty()) continue;
      var rate =
          new EcosRate(
              LocalDate.parse(row.path("TIME").asText(), DateTimeFormatter.BASIC_ISO_DATE),
              new BigDecimal(value));
      if (rate.percent().signum() <= 0) continue;
      if (latest == null || rate.date().isAfter(latest.date())) latest = rate;
    }
    return Optional.ofNullable(latest);
  }
}
