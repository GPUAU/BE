package com.gpumarket.domain.collection.service;

import com.gpumarket.domain.collection.dto.CollectionRunsRequestDto;
import com.gpumarket.domain.collection.dto.CollectionRunsResponseDto;
import com.gpumarket.domain.collection.dto.CollectionRunsResponseDto.CollectionRunDto;
import com.gpumarket.domain.collection.repository.CollectionRunRepository;
import com.gpumarket.domain.collection.repository.CollectionRunRepository.Run;
import com.gpumarket.domain.collection.repository.CollectionRunRepository.Target;
import com.gpumarket.global.api.InvalidRequestException;
import com.gpumarket.global.api.code.status.ErrorStatus;
import com.gpumarket.global.api.exception.GeneralException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionRunService {
  private final CollectionRunRepository repository;

  public CollectionRunService(CollectionRunRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public CollectionRunsResponseDto list(CollectionRunsRequestDto request) {
    String filterKey =
        String.join(
            "|",
            "collection-runs",
            String.valueOf(request.from()),
            String.valueOf(request.to()),
            String.valueOf(request.sourceId()),
            String.valueOf(request.status()));
    UUID fingerprint = UUID.nameUUIDFromBytes(filterKey.getBytes(StandardCharsets.UTF_8));
    var cursor = Cursor.parse(request.cursor(), fingerprint);
    var asOf = utc(cursor.asOf());
    var fetched = repository.find(request, asOf, utc(cursor.afterTime()), cursor.afterId());
    var rows = fetched.subList(0, Math.min(request.limit(), fetched.size()));
    var targets =
        repository.targets(rows.stream().map(Run::id).toList(), request.sourceId(), asOf).stream()
            .collect(
                Collectors.groupingBy(
                    Target::runId, Collectors.mapping(Target::result, Collectors.toList())));
    var items =
        rows.stream()
            .map(
                run ->
                    new CollectionRunDto(
                        Long.toString(run.id()),
                        run.startedAt(),
                        run.finishedAt(),
                        run.status(),
                        run.collectorVersion(),
                        targets.getOrDefault(run.id(), List.of())))
            .toList();

    String next = null;
    if (fetched.size() > request.limit()) {
      Run last = rows.get(rows.size() - 1);
      next = new Cursor(cursor.asOf(), last.startedAt(), last.id()).encode(fingerprint);
    }
    return new CollectionRunsResponseDto(items, cursor.asOf(), next);
  }

  private static OffsetDateTime utc(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private record Cursor(Instant asOf, Instant afterTime, Long afterId) {
    String encode(UUID fingerprint) {
      String raw = asOf + "|" + afterTime + "|" + afterId + "|" + fingerprint;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor parse(String token, UUID fingerprint) {
      Instant now = Instant.now();
      if (token == null) {
        return new Cursor(now, null, null);
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
        if (cursor.afterId() < 1
            || cursor.asOf().isAfter(now)
            || cursor.afterTime().isAfter(cursor.asOf())) {
          throw new IllegalArgumentException();
        }
      } catch (IllegalArgumentException | DateTimeParseException e) {
        throw new InvalidRequestException("수집 실행 이력 커서 또는 조회 조건이 올바르지 않습니다.");
      }
      if (cursor.asOf().isBefore(now.minus(30, ChronoUnit.MINUTES))) {
        throw new GeneralException(ErrorStatus.CURSOR_EXPIRED);
      }
      return cursor;
    }
  }
}
