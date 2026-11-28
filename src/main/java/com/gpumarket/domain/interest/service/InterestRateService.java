package com.gpumarket.domain.interest.service;

import com.gpumarket.domain.interest.dto.LatestInterestRateResponseDto;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 한국은행 ECOS 시장금리(일별) 중 회사채(3년, AA-)를 조회한다. 금리는 하루에 한 번 정도 바뀌므로 DB 없이 12시간 메모리에 둔다.
 *
 * <p>키: 환경변수 ECOS_API_KEY. 없거나 실패하면 NOT_AVAILABLE (원가 에이전트는 금리 제안 없이 진행).
 */
@Service
public class InterestRateService {
  private static final Logger log = LoggerFactory.getLogger(InterestRateService.class);
  static final String BASE = "https://ecos.bok.or.kr/api/StatisticSearch/";
  static final String STAT = "817Y002";
  static final String ITEM = "010300000";
  static final String SERIES = "회사채(3년, AA-)";
  static final Duration TTL = Duration.ofHours(12);
  private static final LatestInterestRateResponseDto.Source SOURCE =
      new LatestInterestRateResponseDto.Source(
          "한국은행 경제통계시스템(ECOS) 시장금리(일별)", STAT, ITEM, "https://ecos.bok.or.kr/");

  /** ECOS 호출 (테스트에서 바꿔 끼울 수 있게). */
  @FunctionalInterface
  public interface EcosFetcher {
    String get(URI uri) throws Exception;
  }

  private final EcosFetcher fetcher;
  private final String apiKey;
  private final Clock clock;
  private LatestInterestRateResponseDto cached;
  private Instant cachedAt;

  @Autowired
  public InterestRateService(@Value("${ECOS_API_KEY:}") String apiKey) {
    this(httpFetcher(), apiKey, Clock.systemUTC());
  }

  public InterestRateService(EcosFetcher fetcher, String apiKey, Clock clock) {
    this.fetcher = fetcher;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.clock = clock;
  }

  public synchronized LatestInterestRateResponseDto latest() {
    Instant now = clock.instant();
    if (cached != null && cachedAt.plus(TTL).isAfter(now)) return cached;
    LatestInterestRateResponseDto fresh = fetch(now);
    if ("AVAILABLE".equals(fresh.status())) { // 실패한 결과는 오래 두지 않는다
      cached = fresh;
      cachedAt = now;
    }
    return fresh;
  }

  private LatestInterestRateResponseDto fetch(Instant now) {
    if (apiKey.isEmpty()) return unavailable("금리 API 키(ECOS_API_KEY)가 설정되지 않아 공시 금리를 제안하지 못합니다.");
    LocalDate today = LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul"));
    var fmt = DateTimeFormatter.BASIC_ISO_DATE;
    URI uri =
        URI.create(
            BASE
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "/json/kr/1/31/"
                + STAT
                + "/D/"
                + today.minusDays(30).format(fmt)
                + "/"
                + today.format(fmt)
                + "/"
                + ITEM);
    try {
      var rate = EcosRateParser.parseLatest(fetcher.get(uri));
      if (rate.isEmpty()) return unavailable("최근 30일 안에 공시된 금리를 찾지 못했습니다.");
      var r = rate.get();
      return new LatestInterestRateResponseDto(
          "AVAILABLE",
          SERIES,
          r.percent().toPlainString(),
          r.percent().divide(BigDecimal.valueOf(100), MathContext.DECIMAL64),
          r.date(),
          now,
          SOURCE,
          r.date().isBefore(today.minusDays(7)) ? List.of("일주일 넘게 지난 공시 금리입니다.") : List.of());
    } catch (Exception e) {
      log.warn("ECOS 금리 조회 실패: {}", e.getClass().getSimpleName()); // 키가 URL에 있으므로 주소·본문은 남기지 않는다
      return unavailable("한국은행 공시 금리를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
  }

  private static LatestInterestRateResponseDto unavailable(String why) {
    return new LatestInterestRateResponseDto(
        "NOT_AVAILABLE", SERIES, null, null, null, null, SOURCE, List.of(why));
  }

  private static EcosFetcher httpFetcher() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    return uri -> {
      var response =
          client.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("HTTP " + response.statusCode());
      return response.body();
    };
  }
}
