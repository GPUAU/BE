package com.gpumarket.domain.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gpumarket.domain.exchange.dto.FxPreviewRequestDto;
import com.gpumarket.domain.exchange.dto.FxPreviewRequestDto.Input;
import com.gpumarket.domain.exchange.repository.ExchangeRepository;
import com.gpumarket.domain.exchange.service.ExchangeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExchangeServiceTest {
  private final ExchangeRepository repository = mock(ExchangeRepository.class);
  private final ExchangeService service =
      new ExchangeService(
          repository, Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneOffset.UTC));

  @Test
  void missingRateIsNotReplacedByAnInventedValue() {
    when(repository.latestRate(any())).thenReturn(Optional.empty());
    var result = service.latest();
    assertThat(result.status()).isEqualTo("NOT_AVAILABLE");
    assertThat(result.rate()).isNull();
    assertThat(result.reason()).isNotBlank();
  }

  @Test
  void previewPreservesOrderDuplicatesAndDistinguishesMissingAndMismatch() {
    when(repository.normalization(10L))
        .thenReturn(
            Optional.of(
                new ExchangeRepository.Normalization(
                    1L, "v1", new BigDecimal("3.000000"), null, "success")));
    when(repository.normalization(99L)).thenReturn(Optional.empty());
    var request =
        new FxPreviewRequestDto(
            List.of(
                new Input("1", "10"),
                new Input("2", "10"),
                new Input("1", "99"),
                new Input("1", "10")),
            "NOMINAL",
            new BigDecimal("1300"));
    var response = service.preview(request);
    assertThat(response.saved()).isFalse();
    assertThat(response.items())
        .extracting("status")
        .containsExactly("OK", "UNAVAILABLE", "NOT_FOUND", "OK");
    assertThat(response.items().get(0).krwPerGpuHour()).isEqualTo("3900.000000");
    assertThat(response.items().get(1).reason()).isEqualTo("OBSERVATION_NORMALIZATION_MISMATCH");
  }

  @Test
  void unavailablePriceDoesNotBecomeZero() {
    when(repository.normalization(10L))
        .thenReturn(
            Optional.of(
                new ExchangeRepository.Normalization(
                    1L, "v1", new BigDecimal("3"), null, "success")));
    var response =
        service.preview(
            new FxPreviewRequestDto(List.of(new Input("1", "10")), "EFFECTIVE", BigDecimal.ONE));
    assertThat(response.items().get(0).status()).isEqualTo("UNAVAILABLE");
    assertThat(response.items().get(0).krwPerGpuHour()).isNull();
  }
}
