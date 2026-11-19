package com.gpumarket.domain.exchange.service;

import com.gpumarket.domain.exchange.dto.FxPreviewRequestDto;
import com.gpumarket.domain.exchange.dto.FxPreviewResponseDto;
import com.gpumarket.domain.exchange.dto.LatestExchangeRateResponseDto;
import com.gpumarket.domain.exchange.repository.ExchangeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExchangeService {
  private final ExchangeRepository repository;
  private final Clock clock;

  @Autowired
  public ExchangeService(ExchangeRepository repository) {
    this(repository, Clock.systemUTC());
  }

  public ExchangeService(ExchangeRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public LatestExchangeRateResponseDto latest() {
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));

    return repository
        .latestRate(today)
        .map(
            rate ->
                new LatestExchangeRateResponseDto(
                    "AVAILABLE",
                    Long.toString(rate.id()),
                    "USD",
                    "KRW",
                    rate.rate().toPlainString(),
                    rate.date(),
                    rate.collectedAt(),
                    new LatestExchangeRateResponseDto.Source(
                        Long.toString(rate.sourceId()), rate.sourceName(), rate.url()),
                    "koreaexim-latest-on-or-before-v1",
                    rate.date().isBefore(today) ? null : false,
                    null,
                    rate.date().isBefore(today)
                        ? List.of("이전 공시일의 환율입니다. 직전 영업일 여부는 확인되지 않았습니다.")
                        : List.of()))
        .orElseGet(
            () ->
                new LatestExchangeRateResponseDto(
                    "NOT_AVAILABLE",
                    null,
                    "USD",
                    "KRW",
                    null,
                    null,
                    null,
                    null,
                    "koreaexim-latest-on-or-before-v1",
                    null,
                    "한국수출입은행의 저장된 USD/KRW 환율이 없습니다.",
                    List.of()));
  }

  @Transactional(readOnly = true)
  public FxPreviewResponseDto preview(FxPreviewRequestDto request) {
    var items =
        request.items().stream()
            .map(
                input ->
                    repository
                        .normalization(Long.parseLong(input.normalizationId()))
                        .map(
                            normalization ->
                                item(
                                    input, normalization, request.priceType(), request.krwPerUsd()))
                        .orElseGet(
                            () ->
                                new FxPreviewResponseDto.Item(
                                    input.observationId(),
                                    input.normalizationId(),
                                    null,
                                    null,
                                    null,
                                    "KRW/GPU-hour",
                                    "NOT_FOUND",
                                    "ID_NOT_FOUND")))
            .toList();

    return new FxPreviewResponseDto(
        "USER_INPUT",
        request.priceType(),
        "USD",
        "KRW",
        request.krwPerUsd().toPlainString(),
        items,
        false,
        "직접 입력한 환율을 적용한 일회성 계산입니다.");
  }

  private FxPreviewResponseDto.Item item(
      FxPreviewRequestDto.Input input,
      ExchangeRepository.Normalization normalization,
      String type,
      BigDecimal rate) {
    String status = "OK";
    String reason = null;
    BigDecimal usd = type.equals("NOMINAL") ? normalization.nominal() : normalization.effective();

    if (normalization.observationId() != Long.parseLong(input.observationId())) {
      status = "UNAVAILABLE";
      reason = "OBSERVATION_NORMALIZATION_MISMATCH";
      usd = null;
    } else if (!"success".equalsIgnoreCase(normalization.status())) {
      status = "UNAVAILABLE";
      reason = "NORMALIZATION_UNAVAILABLE";
      usd = null;
    } else if (usd == null) {
      status = "UNAVAILABLE";
      reason = "PRICE_TYPE_MISMATCH";
    }

    // 표시용 단가만 계산하고 사용자 입력 환율은 저장하지 않는다.
    String krw =
        usd == null ? null : usd.multiply(rate).setScale(6, RoundingMode.HALF_UP).toPlainString();

    return new FxPreviewResponseDto.Item(
        input.observationId(),
        input.normalizationId(),
        normalization.ruleVersion(),
        usd == null ? null : usd.toPlainString(),
        krw,
        "KRW/GPU-hour",
        status,
        reason);
  }
}
