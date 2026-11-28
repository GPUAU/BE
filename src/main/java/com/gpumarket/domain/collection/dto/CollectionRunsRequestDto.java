package com.gpumarket.domain.collection.dto;

import com.gpumarket.global.api.InvalidRequestException;
import com.gpumarket.global.api.code.status.ErrorStatus;
import com.gpumarket.global.api.exception.GeneralException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.MultiValueMap;

public record CollectionRunsRequestDto(
    OffsetDateTime from,
    OffsetDateTime to,
    Long sourceId,
    String status,
    int limit,
    String cursor) {
  private static final Set<String> FIELDS =
      Set.of("from", "to", "sourceId", "status", "limit", "cursor");
  private static final Set<String> STATUSES =
      Set.of("running", "success", "partial_failure", "failed");

  public static CollectionRunsRequestDto parse(MultiValueMap<String, String> input) {
    if (!FIELDS.containsAll(input.keySet())
        || input.values().stream().anyMatch(values -> values.size() != 1)) {
      throw new GeneralException(ErrorStatus.INVALID_REQUEST, "지원하지 않거나 중복된 수집 실행 조회 조건입니다.");
    }

    String sourceText = input.getFirst("sourceId");
    String statusText = input.getFirst("status");
    String limitText = input.getFirst("limit");
    try {
      var from = timestamp(input.getFirst("from"));
      var to = timestamp(input.getFirst("to"));
      Long source = sourceText == null ? null : Long.valueOf(sourceText);
      String state = statusText == null ? null : statusText.toLowerCase(Locale.ROOT);
      int limit = limitText == null ? 50 : Integer.parseInt(limitText);
      if ((from != null && to != null && !from.isBefore(to))
          || (source != null && source < 1)
          || limit < 1
          || limit > 100
          || (state != null && !STATUSES.contains(state))) {
        throw new InvalidRequestException("조회 기간, 출처 ID, 실행 상태 또는 페이지 크기를 확인해 주세요.");
      }
      return new CollectionRunsRequestDto(from, to, source, state, limit, input.getFirst("cursor"));
    } catch (DateTimeParseException | NumberFormatException e) {
      throw new GeneralException(
          ErrorStatus.INVALID_REQUEST, "시각은 ISO 8601, 출처 ID와 페이지 크기는 정수로 입력해 주세요.");
    }
  }

  private static OffsetDateTime timestamp(String value) {
    return value == null ? null : OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);
  }
}
