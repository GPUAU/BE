package com.gpumarket.domain.exchange.controller;

import com.gpumarket.domain.exchange.dto.FxPreviewRequestDto;
import com.gpumarket.domain.exchange.dto.FxPreviewResponseDto;
import com.gpumarket.domain.exchange.dto.LatestExchangeRateResponseDto;
import com.gpumarket.domain.exchange.service.ExchangeService;
import com.gpumarket.global.api.ApiResponse;
import com.gpumarket.global.api.InvalidRequestException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExchangeController {
  private final ExchangeService service;

  public ExchangeController(ExchangeService service) {
    this.service = service;
  }

  @GetMapping("/exchange-rates/latest")
  public ApiResponse<LatestExchangeRateResponseDto> latest(
      @RequestParam(defaultValue = "USD") String baseCurrency,
      @RequestParam(defaultValue = "KRW") String quoteCurrency) {
    if (!baseCurrency.equals("USD") || !quoteCurrency.equals("KRW")) {
      throw new InvalidRequestException("지원하지 않는 통화쌍입니다. USD/KRW만 지원합니다.");
    }

    return ApiResponse.success(service.latest());
  }

  @PostMapping("/exchange-rate-simulations")
  public ApiResponse<FxPreviewResponseDto> preview(
      @Valid @RequestBody FxPreviewRequestDto request) {
    return ApiResponse.success(service.preview(request));
  }
}
