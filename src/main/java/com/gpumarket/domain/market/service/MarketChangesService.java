package com.gpumarket.domain.market.service;

import com.gpumarket.domain.exchange.dto.LatestExchangeRateResponseDto.Source;
import com.gpumarket.domain.market.dto.MarketChangesRequestDto;
import com.gpumarket.domain.market.dto.MarketChangesResponseDto;
import com.gpumarket.domain.market.dto.MarketChangesResponseDto.KrwDecompositionDto;
import com.gpumarket.domain.market.dto.MarketChangesResponseDto.PriceChangeDto;
import com.gpumarket.domain.market.dto.MarketChangesResponseDto.PriceUnitDto;
import com.gpumarket.domain.market.repository.MarketChangesRepository;
import com.gpumarket.domain.market.repository.MarketOfferRepository;
import com.gpumarket.global.api.InvalidRequestException;
import com.gpumarket.global.api.code.status.ErrorStatus;
import com.gpumarket.global.api.exception.GeneralException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MarketChangesService {
  private final MarketChangesRepository repository;
  private final MarketOfferRepository offers;

  public MarketChangesService(MarketChangesRepository repository, MarketOfferRepository offers) {
    this.repository = repository;
    this.offers = offers;
  }

  public MarketChangesResponseDto list(MarketChangesRequestDto request) {
    var filters = request.filters();
    var fingerprint =
        UUID.nameUUIDFromBytes(
            (filters + "|" + request.includeKrwDecomposition()).getBytes(StandardCharsets.UTF_8));
    var cursor = Cursor.parse(request.cursor(), request.from().toInstant(), fingerprint);
    var asOf = cursor.asOf().atOffset(ZoneOffset.UTC);
    filters.put("asOf", asOf);
    filters.put("afterTime", cursor.afterTime().atOffset(ZoneOffset.UTC));
    filters.put("afterId", cursor.afterId());
    filters.put("size", request.limit() + 1);

    var fetched = repository.events(filters);
    var rows = fetched.subList(0, Math.min(request.limit(), fetched.size()));
    var evidenceIds = ids(rows, "previous_check_id", "current_check_id", "undisclosed_check_id");
    var evidence =
        offers.evidence(evidenceIds).stream()
            .collect(Collectors.groupingBy(row -> id(row, "check_id")));
    Map<Long, List<Map<String, Object>>> rates = Map.of();
    if (request.includeKrwDecomposition()) {
      var changes =
          rows.stream().filter(row -> "PRICE_CHANGE".equals(row.get("event_type"))).toList();
      rates =
          repository
              .conversions(
                  ids(changes, "previous_normalization_id", "current_normalization_id"), asOf)
              .stream()
              .collect(Collectors.groupingBy(row -> id(row, "normalization_id")));
    }

    var items = new ArrayList<PriceChangeDto>();
    for (var row : rows) {
      items.add(event(row, evidence, rates, request.includeKrwDecomposition()));
    }
    String next = null;
    if (fetched.size() > request.limit()) {
      var last = rows.get(rows.size() - 1);
      next =
          new Cursor(cursor.asOf(), time(last, "current_at"), id(last, "current_id"))
              .encode(fingerprint);
    }
    return new MarketChangesResponseDto(items, cursor.asOf(), next);
  }

  private static PriceChangeDto event(
      Map<String, Object> row,
      Map<Long, List<Map<String, Object>>> evidence,
      Map<Long, List<Map<String, Object>>> rates,
      boolean includeKrw) {
    var type = str(row, "event_type");
    boolean comparable = "PRICE_CHANGE".equals(type);
    var previous = (BigDecimal) row.get("previous_price");
    var current = (BigDecimal) row.get("current_price");
    var change = comparable ? current.subtract(previous) : null;
    var previousAt = time(row, "previous_at");
    var currentAt = time(row, "current_at");
    var oldEvidence = forId(evidence, id(row, "previous_check_id"));
    var newEvidence = forId(evidence, id(row, "current_check_id"));
    var undisclosed = forId(evidence, id(row, "undisclosed_check_id"));

    return new PriceChangeDto(
        "observation-" + str(row, "current_id"),
        currentAt,
        type,
        str(row, "offer_id"),
        previousAt == null ? null : str(row, "offer_id"),
        str(row, "offer_id"),
        str(row, "previous_id"),
        str(row, "current_id"),
        str(row, "previous_normalization_id"),
        str(row, "current_normalization_id"),
        str(row, "previous_rule"),
        str(row, "current_rule"),
        decimal(previous),
        decimal(current),
        decimal(change),
        comparable && previous.signum() != 0
            ? decimal(change.divide(previous, 6, RoundingMode.HALF_UP))
            : null,
        previousAt == null ? currentAt : null,
        previousAt,
        currentAt,
        values(oldEvidence, "evidence_id"),
        values(newEvidence, "evidence_id"),
        values(oldEvidence, "source_id"),
        values(newEvidence, "source_id"),
        str(row, "undisclosed_check_id"),
        time(row, "undisclosed_at"),
        values(undisclosed, "evidence_id"),
        undisclosed.stream().map(MarketOfferService::evidence).toList(),
        unit(row, "previous"),
        unit(row, "current"),
        switch (type) {
          case "UNIT_CHANGE" -> "ORIGINAL_UNIT_CHANGED";
          case "BECAME_PUBLIC" -> "OFFICIAL_DISCLOSURE_CONFIRMED";
          default -> comparable && previous.signum() == 0 ? "BASE_PRICE_ZERO" : null;
        },
        includeKrw ? decomposition(row, rates, comparable, previous, current) : null);
  }

  private static KrwDecompositionDto decomposition(
      Map<String, Object> row,
      Map<Long, List<Map<String, Object>>> rates,
      boolean comparable,
      BigDecimal previous,
      BigDecimal current) {
    var before =
        comparable
            ? uniqueRate(rates, id(row, "previous_normalization_id"))
            : Map.<String, Object>of();
    var after =
        comparable
            ? uniqueRate(rates, id(row, "current_normalization_id"))
            : Map.<String, Object>of();
    var beforeRate = (BigDecimal) before.get("rate");
    var afterRate = (BigDecimal) after.get("rate");
    boolean available = beforeRate != null && afterRate != null;
    return new KrwDecompositionDto(
        available ? "OK" : comparable ? "UNAVAILABLE" : "NOT_APPLICABLE",
        available ? null : comparable ? "HISTORICAL_AUTO_RATE_UNAVAILABLE" : "NON_COMPARABLE_EVENT",
        str(row, "previous_id"),
        str(row, "current_id"),
        str(before, "exchange_rate_id"),
        str(after, "exchange_rate_id"),
        decimal(previous),
        decimal(current),
        decimal(beforeRate),
        decimal(afterRate),
        date(before),
        date(after),
        source(before),
        source(after),
        null,
        null,
        available ? decimal(current.subtract(previous).multiply(beforeRate)) : null,
        available ? decimal(current.multiply(afterRate.subtract(beforeRate))) : null);
  }

  private static Map<String, Object> uniqueRate(
      Map<Long, List<Map<String, Object>>> rates, Long id) {
    var candidates = forId(rates, id);
    return candidates.size() == 1 ? candidates.get(0) : Map.of();
  }

  private static List<Map<String, Object>> forId(
      Map<Long, List<Map<String, Object>>> rows, Long id) {
    return id == null ? List.of() : rows.getOrDefault(id, List.of());
  }

  private static Source source(Map<String, Object> rate) {
    return rate.isEmpty()
        ? null
        : new Source(str(rate, "source_id"), str(rate, "source_name"), str(rate, "source_url"));
  }

  private static LocalDate date(Map<String, Object> row) {
    Object value = row.get("reference_date");
    return value instanceof Date date ? date.toLocalDate() : (LocalDate) value;
  }

  private static PriceUnitDto unit(Map<String, Object> row, String prefix) {
    if (row.get(prefix + "_id") == null) {
      return null;
    }
    return new PriceUnitDto(
        upper(str(row, prefix + "_currency")),
        upper(str(row, prefix + "_time_unit")),
        upper(str(row, prefix + "_applies_to")));
  }

  private static String upper(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  private static List<Long> ids(List<Map<String, Object>> rows, String... columns) {
    return rows.stream()
        .flatMap(row -> Stream.of(columns).map(column -> id(row, column)))
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private static List<String> values(List<Map<String, Object>> rows, String column) {
    return rows.stream().map(row -> str(row, column)).filter(Objects::nonNull).distinct().toList();
  }

  private static Long id(Map<String, Object> row, String key) {
    return row.get(key) instanceof Number number ? number.longValue() : null;
  }

  private static String str(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), null);
  }

  private static Instant time(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return value instanceof OffsetDateTime offset ? offset.toInstant() : (Instant) value;
  }

  private static String decimal(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private record Cursor(Instant asOf, Instant afterTime, long afterId) {
    String encode(UUID fingerprint) {
      var value = asOf + "|" + afterTime + "|" + afterId + "|" + fingerprint;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor parse(String token, Instant start, UUID fingerprint) {
      Instant now = Instant.now();
      if (token == null) {
        return new Cursor(now, start, 0);
      }
      Cursor cursor;
      try {
        if (token.length() > 240) {
          throw new IllegalArgumentException();
        }
        String[] parts =
            new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 4 || !fingerprint.toString().equals(parts[3])) {
          throw new IllegalArgumentException();
        }
        cursor =
            new Cursor(Instant.parse(parts[0]), Instant.parse(parts[1]), Long.parseLong(parts[2]));
        if (cursor.afterId() <= 0
            || cursor.afterTime().isBefore(start)
            || cursor.afterTime().isAfter(cursor.asOf())
            || cursor.asOf().isAfter(now)) {
          throw new IllegalArgumentException();
        }
      } catch (java.time.format.DateTimeParseException | IllegalArgumentException e) {
        throw new InvalidRequestException("가격 변화 커서 또는 조회 조건이 올바르지 않습니다.");
      }
      if (cursor.asOf().isBefore(now.minus(30, ChronoUnit.MINUTES))) {
        throw new GeneralException(ErrorStatus.CURSOR_EXPIRED);
      }
      return cursor;
    }
  }
}
