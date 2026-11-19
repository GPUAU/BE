package com.gpumarket.domain.market.service;

import com.gpumarket.domain.market.dto.MarketAvailabilityRequestDto;
import com.gpumarket.domain.market.dto.MarketAvailabilityResponseDto;
import com.gpumarket.domain.market.dto.MarketAvailabilityResponseDto.AvailabilityDto;
import com.gpumarket.domain.market.dto.MarketAvailabilityResponseDto.DisclosureDto;
import com.gpumarket.domain.market.dto.MarketAvailabilityResponseDto.FreshnessDto;
import com.gpumarket.domain.market.dto.MarketAvailabilityResponseDto.ProcessingCheckDto;
import com.gpumarket.domain.market.repository.MarketAvailabilityRepository;
import com.gpumarket.domain.market.repository.MarketOfferRepository;
import com.gpumarket.global.api.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MarketAvailabilityService {
  private final MarketAvailabilityRepository repository;
  private final MarketOfferRepository offers;

  public MarketAvailabilityService(
      MarketAvailabilityRepository repository, MarketOfferRepository offers) {
    this.repository = repository;
    this.offers = offers;
  }

  public MarketAvailabilityResponseDto list(MarketAvailabilityRequestDto request) {
    var filters = new LinkedHashMap<String, Object>();
    filters.put("modelId", request.modelId());
    filters.put("countryCode", request.countryCode());
    filters.put("contractType", request.contractType());
    if (!request.providerIds().isEmpty()) {
      filters.put("providerIds", request.providerIds());
    }
    var fingerprint =
        UUID.nameUUIDFromBytes(("availability|" + filters).getBytes(StandardCharsets.UTF_8));
    var cursor = Cursor.parse(request.cursor(), fingerprint);
    filters.put("asOf", cursor.asOf().atOffset(ZoneOffset.UTC));
    filters.put("afterId", cursor.afterId());
    filters.put("size", request.limit() + 1);

    var fetched = repository.find(filters);
    var rows = fetched.subList(0, Math.min(fetched.size(), request.limit()));
    var related = evidenceAt(MarketOfferService.related(offers, rows), cursor.asOf());
    var items = rows.stream().map(row -> item(row, related, cursor.asOf())).toList();
    String next =
        fetched.size() > request.limit()
            ? new Cursor(
                    cursor.asOf(),
                    ((Number) rows.get(rows.size() - 1).get("target_id")).longValue())
                .encode(fingerprint)
            : null;
    return new MarketAvailabilityResponseDto(items, cursor.asOf(), next);
  }

  private static AvailabilityDto item(
      Map<String, Object> row, MarketOfferService.Related related, Instant asOf) {
    String disclosure =
        switch (Objects.toString(row.get("disclosure_status"), "")) {
          case "public" -> "PUBLIC";
          case "contact_sales" -> "QUOTE_REQUIRED";
          case "login_required" -> "LOGIN_REQUIRED";
          case "not_disclosed" -> "NOT_PUBLIC";
          default -> "UNKNOWN";
        };
    String processing =
        switch (Objects.toString(row.get("processing_status"), "")) {
          case "" -> "UNKNOWN";
          case "unsupported_path" -> "UNSUPPORTED";
          default -> row.get("processing_status").toString().toUpperCase(Locale.ROOT);
        };
    Instant lastSuccess = time(row, "confirmed_at");
    return new AvailabilityDto(
        string(row, "target_id"),
        string(row, "provider_id"),
        string(row, "gpu_model_id"),
        string(row, "product_id"),
        string(row, "offer_id"),
        new DisclosureDto(
            disclosure,
            time(row, "disclosure_checked_at"),
            checkEvidence(row, related, "disclosure_check_id")),
        new ProcessingCheckDto(processing, time(row, "checked_at"), string(row, "check_reason")),
        string(row, "scope_description"),
        checkEvidence(row, related, "check_id"),
        time(row, "first_price_observed_at"),
        MarketOfferService.observation(row, related, null),
        new FreshnessDto(
            MarketOfferService.freshness(lastSuccess, asOf), lastSuccess, asOf, "48h-v1"));
  }

  private static MarketOfferService.Related evidenceAt(
      MarketOfferService.Related related, Instant asOf) {
    var evidence =
        related.evidence().entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry ->
                        entry.getValue().stream()
                            .filter(item -> !isAfter(item, "capturedAt", asOf))
                            .map(item -> evidenceVerificationAt(item, asOf))
                            .toList()));
    return new MarketOfferService.Related(evidence, related.components());
  }

  private static Map<String, Object> evidenceVerificationAt(
      Map<String, Object> item, Instant asOf) {
    if (!isAfter(item, "verifiedAt", asOf)) {
      return item;
    }
    var original = new LinkedHashMap<>(item);
    original.put("primaryVerified", null);
    original.put("verifiedAt", null);
    return original;
  }

  private static boolean isAfter(Map<String, Object> item, String field, Instant asOf) {
    return item.get(field) instanceof Instant timestamp && timestamp.isAfter(asOf);
  }

  private static List<Map<String, Object>> checkEvidence(
      Map<String, Object> row, MarketOfferService.Related related, String check) {
    return row.get(check) instanceof Number id
        ? related.evidence().getOrDefault(id.longValue(), List.of())
        : List.of();
  }

  private static String string(Map<String, Object> row, String field) {
    return Objects.toString(row.get(field), null);
  }

  private static Instant time(Map<String, Object> row, String field) {
    Object value = row.get(field);
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return value instanceof OffsetDateTime offset ? offset.toInstant() : (Instant) value;
  }

  private record Cursor(Instant asOf, long afterId) {
    String encode(UUID fingerprint) {
      String value = asOf + "|" + afterId + "|" + fingerprint;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor parse(String token, UUID fingerprint) {
      Instant now = Instant.now();
      if (token == null) {
        return new Cursor(now, 0);
      }
      Cursor cursor;
      try {
        if (token.length() > 180) {
          throw new IllegalArgumentException();
        }
        String[] parts =
            new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 3 || !fingerprint.toString().equals(parts[2])) {
          throw new IllegalArgumentException();
        }
        cursor = new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
        if (cursor.afterId() <= 0 || cursor.asOf().isAfter(now)) {
          throw new IllegalArgumentException();
        }
      } catch (IllegalArgumentException | DateTimeParseException e) {
        throw new InvalidRequestException("가격 확보 상태 커서 또는 조회 조건이 올바르지 않습니다.");
      }
      if (cursor.asOf().isBefore(now.minus(30, ChronoUnit.MINUTES))) {
        throw new ResponseStatusException(HttpStatus.GONE, "커서가 만료되었습니다. 첫 페이지부터 조회하세요.");
      }
      return cursor;
    }
  }
}
