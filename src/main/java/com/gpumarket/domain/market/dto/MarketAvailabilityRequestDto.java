package com.gpumarket.domain.market.dto;

import com.gpumarket.global.api.InvalidRequestException;
import com.gpumarket.global.api.code.status.ErrorStatus;
import com.gpumarket.global.api.exception.GeneralException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.MultiValueMap;

public record MarketAvailabilityRequestDto(
    long modelId,
    List<Long> providerIds,
    String countryCode,
    String contractType,
    int limit,
    String cursor) {

  public static MarketAvailabilityRequestDto parse(MultiValueMap<String, String> input) {
    var fields = Set.of("modelId", "providerIds", "countryCode", "contractType", "limit", "cursor");
    if (!fields.containsAll(input.keySet())
        || input.entrySet().stream()
            .anyMatch(e -> !e.getKey().equals("providerIds") && e.getValue().size() != 1)) {
      throw new GeneralException(ErrorStatus.INVALID_REQUEST, "지원하지 않거나 중복된 가격 확보 상태 조회 조건입니다.");
    }
    String model = input.getFirst("modelId");
    if (model == null) {
      throw new InvalidRequestException("모델 ID가 필요합니다.");
    }
    try {
      long modelId = Long.parseLong(model);
      String sizeText = input.getFirst("limit");
      int size = sizeText == null ? 50 : Integer.parseInt(sizeText);
      var providers =
          input.getOrDefault("providerIds", List.of()).stream()
              .flatMap(value -> Arrays.stream(value.split(",", -1)))
              .map(Long::parseLong)
              .distinct()
              .sorted()
              .toList();
      String country = lower(input.getFirst("countryCode"));
      String contract = lower(input.getFirst("contractType"));
      if (modelId < 1
          || size < 1
          || size > 100
          || providers.size() > 100
          || providers.stream().anyMatch(id -> id < 1)
          || (country != null && !country.matches("[a-z]{2}"))
          || (contract != null
              && !Set.of("on_demand", "spot", "commitment", "unknown").contains(contract))) {
        throw new InvalidRequestException("모델 ID, 사업자 ID, 국가·계약 조건과 페이지 크기를 확인해 주세요.");
      }
      return new MarketAvailabilityRequestDto(
          modelId, providers, country, contract, size, input.getFirst("cursor"));
    } catch (NumberFormatException e) {
      throw new GeneralException(ErrorStatus.INVALID_REQUEST, "모델 ID, 사업자 ID 및 페이지 크기는 정수여야 합니다.");
    }
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
