package com.gpumarket.domain.exchange.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public final class KoreaEximCollector {
  private static final String URL =
      "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON";

  private KoreaEximCollector() {}

  public static Optional<BigDecimal> parseRate(String body) {
    try {
      JsonNode rows = new ObjectMapper().readTree(body);
      if (!rows.isArray()) throw new IllegalArgumentException("Unexpected bank response");
      if (rows.isEmpty()) return Optional.empty();
      for (JsonNode row : rows) {
        if (row.path("result").asInt() != 1)
          throw new IllegalArgumentException("Bank request rejected");
        if ("USD".equals(row.path("cur_unit").asText())) {
          BigDecimal value = new BigDecimal(row.path("deal_bas_r").asText().replace(",", ""));
          if (value.signum() <= 0) throw new IllegalArgumentException("Invalid rate");
          return Optional.of(value);
        }
      }
      throw new IllegalArgumentException("USD rate missing");
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid bank response");
    }
  }

  public static void main(String[] args) {
    String key = System.getenv("KOREAEXIM_EXCHANGE_API_KEY");
    String jdbcUrl = System.getenv("DATABASE_URL"),
        user = System.getenv("DATABASE_USER"),
        password = System.getenv("DATABASE_PASSWORD");
    if (key == null || key.isBlank() || jdbcUrl == null || user == null || password == null) {
      System.err.println("환율 API 키 또는 DB 환경변수가 설정되지 않았습니다.");
      System.exit(1);
    }
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    for (int i = 0; i < 10; i++) {
      LocalDate date = today.minusDays(i);
      try {
        String query =
            "?authkey="
                + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&searchdate="
                + date.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "&data=AP01";
        var request =
            HttpRequest.newBuilder(URI.create(URL + query))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
          System.err.println("환율 API HTTP 오류: " + response.statusCode());
          System.exit(1);
        }
        Optional<BigDecimal> rate = parseRate(response.body());
        if (rate.isEmpty()) continue;
        try (var connection = DriverManager.getConnection(jdbcUrl, user, password)) {
          connection.setAutoCommit(false);
          try {
            try (var source =
                connection.prepareStatement(
                    "INSERT INTO data_source"
                        + " (source_name,source_type,base_url,collection_method,terms_status)"
                        + " VALUES ('koreaexim_usd_krw_api','fx_source',?,'api','unknown') ON"
                        + " CONFLICT (source_name) DO NOTHING")) {
              source.setString(1, URL);
              source.executeUpdate();
            }
            int inserted;
            try (var statement =
                connection.prepareStatement(
                    "INSERT INTO exchange_rate"
                        + " (source_id,base_currency,quote_currency,rate,reference_date,collected_at,source_url)"
                        + " SELECT source_id,'USD','KRW',?,?,CURRENT_TIMESTAMP,? FROM data_source"
                        + " WHERE source_name='koreaexim_usd_krw_api' ON CONFLICT"
                        + " (source_id,base_currency,quote_currency,reference_date) DO NOTHING")) {
              statement.setBigDecimal(1, rate.get());
              statement.setObject(2, date);
              statement.setString(3, URL);
              inserted = statement.executeUpdate();
            }
            connection.commit();
            System.out.println(
                "환율 "
                    + date
                    + " · USD/KRW "
                    + rate.get().toPlainString()
                    + " · "
                    + (inserted == 1 ? "저장 완료" : "이미 저장됨"));
          } catch (Exception ex) {
            connection.rollback();
            throw ex;
          }
        }
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.err.println("환율 요청이 중단됐습니다.");
        System.exit(1);
      } catch (Exception e) {
        System.err.println("환율 수집 또는 저장에 실패했습니다. 응답·DB 설정을 확인해 주세요.");
        System.exit(1);
      }
    }
    System.err.println("최근 10일 이내 공시 환율을 찾지 못했습니다.");
    System.exit(1);
  }
}
