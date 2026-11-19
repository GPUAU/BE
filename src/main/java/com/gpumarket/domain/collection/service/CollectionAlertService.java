package com.gpumarket.domain.collection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpumarket.domain.collection.dto.CollectionAlertsResponseDto;
import com.gpumarket.domain.collection.repository.CollectionAlertRepository;
import com.gpumarket.global.api.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionAlertService {
  private static final Set<String> TYPES =
      Set.of(
          "PRICE_SPIKE", "COLLECTION_FAILED", "MISSING", "FX_FAILED", "FX_STALE", "CONFIG_CHANGED");
  private static final Set<String> STATUSES = Set.of("OPEN", "INVESTIGATING", "RESOLVED");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final CollectionAlertRepository repository;
  private final Clock clock;

  @Autowired
  public CollectionAlertService(CollectionAlertRepository repository) {
    this(repository, Clock.systemUTC());
  }

  public CollectionAlertService(CollectionAlertRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public record Cursor(
      String asOf,
      String afterTime,
      long afterId,
      String from,
      String to,
      String alertType,
      String handlingStatus,
      int limit) {}

  private String stamp(Instant value) {
    return value == null ? null : value.toString();
  }

  private String encode(Cursor cursor) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(cursor));
    } catch (Exception e) {
      throw new IllegalStateException("커서 생성 오류", e);
    }
  }

  private Cursor decode(String value) {
    try {
      if (value.length() > 2048) {
        throw new IllegalArgumentException();
      }

      byte[] decoded = Base64.getUrlDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
      return JSON.readValue(decoded, Cursor.class);
    } catch (Exception e) {
      throw new InvalidRequestException("잘못된 커서입니다.");
    }
  }

  @Transactional(readOnly = true)
  public CollectionAlertsResponseDto list(
      Instant from, Instant to, String alertType, String handlingStatus, int limit, String token) {
    if (limit < 1
        || limit > 100
        || (from != null && to != null && !from.isBefore(to))
        || (alertType != null && !TYPES.contains(alertType))
        || (handlingStatus != null && !STATUSES.contains(handlingStatus))) {
      throw new InvalidRequestException("조회 조건이 유효하지 않습니다.");
    }

    Instant asOf = clock.instant();
    Instant after = null;
    Long id = null;

    if (token != null) {
      Cursor cursor = decode(token);

      // 이전 페이지와 조건이 다르면 다른 결과 집합의 커서를 사용할 수 없다.
      if (!Objects.equals(cursor.from(), stamp(from))
          || !Objects.equals(cursor.to(), stamp(to))
          || !Objects.equals(cursor.alertType(), alertType)
          || !Objects.equals(cursor.handlingStatus(), handlingStatus)
          || cursor.limit() != limit
          || cursor.afterId() < 1) {
        throw new InvalidRequestException("커서와 조회 조건이 다릅니다.");
      }

      try {
        asOf = Instant.parse(cursor.asOf());
        after = Instant.parse(cursor.afterTime());
        id = cursor.afterId();

        if (asOf.isAfter(clock.instant()) || after.isAfter(asOf)) {
          throw new IllegalArgumentException();
        }
      } catch (Exception e) {
        throw new InvalidRequestException("잘못된 커서입니다.");
      }
    }

    var found = repository.find(asOf, from, to, alertType, handlingStatus, after, id, limit + 1);
    boolean more = found.size() > limit;
    var items = more ? found.subList(0, limit) : found;
    String next = null;

    if (more) {
      var last = items.get(items.size() - 1);
      next =
          encode(
              new Cursor(
                  asOf.toString(),
                  last.detectedAt().toString(),
                  Long.parseLong(last.alertId()),
                  stamp(from),
                  stamp(to),
                  alertType,
                  handlingStatus,
                  limit));
    }

    return new CollectionAlertsResponseDto(items, asOf, next);
  }
}
