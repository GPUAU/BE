package com.gpumarket.domain.market.dto;

import com.gpumarket.global.api.InvalidRequestException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;

public record MarketChangesRequestDto(
    long modelId,
    OffsetDateTime from,
    OffsetDateTime to,
    List<Long> providerIds,
    String countryCode,
    String regionCode,
    String contractType,
    boolean includeKrwDecomposition,
    int limit,
    String cursor) {
  public static MarketChangesRequestDto parse(MultiValueMap<String, String> input) {
    var fields =
        Set.of(
            "modelId",
            "from",
            "to",
            "providerIds",
            "countryCode",
            "regionCode",
            "contractType",
            "includeKrwDecomposition",
            "limit",
            "cursor");
    if (!fields.containsAll(input.keySet())
        || input.entrySet().stream()
            .anyMatch(e -> !e.getKey().equals("providerIds") && e.getValue().size() != 1)) {
      throw new InvalidRequestException("지원하지 않거나 중복된 가격 변화 조회 조건입니다.");
    }
    if (!input.keySet().containsAll(Set.of("modelId", "from", "to"))) {
      throw new InvalidRequestException("모델 ID와 조회 시작·종료 시각이 필요합니다.");
    }
    try {
      long model = Long.parseLong(input.getFirst("modelId"));
      var start =
          OffsetDateTime.parse(input.getFirst("from")).withOffsetSameInstant(ZoneOffset.UTC);
      var end = OffsetDateTime.parse(input.getFirst("to")).withOffsetSameInstant(ZoneOffset.UTC);
      String limitValue = input.getFirst("limit");
      int size = limitValue == null ? 50 : Integer.parseInt(limitValue);
      var providers =
          input.getOrDefault("providerIds", List.of()).stream()
              .flatMap(value -> Arrays.stream(value.split(",", -1)))
              .map(Long::parseLong)
              .distinct()
              .sorted()
              .toList();
      String contract = text(input.getFirst("contractType"));
      String decomposition = input.getFirst("includeKrwDecomposition");
      if (model < 1
          || !start.isBefore(end)
          || Duration.between(start, end).compareTo(Duration.ofDays(731)) > 0
          || size < 1
          || size > 100
          || providers.size() > 100
          || providers.stream().anyMatch(id -> id < 1)
          || (contract != null
              && !Set.of("on_demand", "spot", "commitment", "unknown").contains(contract))
          || (decomposition != null && !Set.of("true", "false").contains(decomposition))) {
        throw new InvalidRequestException("모델, 조회 기간 또는 가격 변화 필터의 범위를 확인해 주세요.");
      }
      return new MarketChangesRequestDto(
          model,
          start,
          end,
          providers,
          text(input.getFirst("countryCode")),
          text(input.getFirst("regionCode")),
          contract,
          Boolean.parseBoolean(decomposition),
          size,
          input.getFirst("cursor"));
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new InvalidRequestException("모델 ID, 조회 기간 및 필터 형식을 확인해 주세요.");
    }
  }

  public Map<String, Object> filters() {
    var filters = new LinkedHashMap<String, Object>();
    filters.put("modelId", modelId);
    filters.put("start", from);
    filters.put("end", to);
    if (!providerIds.isEmpty()) {
      filters.put("providerIds", providerIds);
    }
    if (countryCode != null) {
      filters.put("countryCode", countryCode);
    }
    if (regionCode != null) {
      filters.put("regionCode", regionCode);
    }
    if (contractType != null) {
      filters.put("contractType", contractType);
    }
    return filters;
  }

  private static String text(String value) {
    if (value == null) {
      return null;
    }
    if (!value.matches("[A-Za-z0-9_-]{1,80}")) {
      throw new InvalidRequestException("국가, 리전 또는 계약 유형 형식을 확인해 주세요.");
    }
    return value.toLowerCase(Locale.ROOT);
  }
}
