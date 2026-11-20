package com.gpumarket.domain.simulation.controller;

import com.gpumarket.domain.simulation.dto.MonteCarloForecastRequestDto;
import com.gpumarket.domain.simulation.dto.MonteCarloPricingRequestDto;
import com.gpumarket.domain.simulation.dto.MonteCarloResponseDtos.Forecast;
import com.gpumarket.domain.simulation.dto.MonteCarloResponseDtos.Pricing;
import com.gpumarket.domain.simulation.service.MonteCarloService;
import com.gpumarket.global.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MonteCarloController {
  private final MonteCarloService service;

  public MonteCarloController(MonteCarloService service) {
    this.service = service;
  }

  @Operation(summary = "몬테카를로 12개월 가격 범위 시뮬레이션")
  @PostMapping("/api/v1/monte-carlo-forecasts")
  public ApiResponse<Forecast> forecast(@Valid @RequestBody MonteCarloForecastRequestDto request) {
    return ApiResponse.success(service.forecast(request));
  }

  @Operation(summary = "몬테카를로 목표 IRR 달성 확률별 판매가격 시뮬레이션")
  @PostMapping("/api/v1/monte-carlo-pricing-simulations")
  public ApiResponse<Pricing> pricing(@Valid @RequestBody MonteCarloPricingRequestDto request) {
    return ApiResponse.success(service.pricing(request));
  }

  @ExceptionHandler(MonteCarloService.CalculationInProgress.class)
  ResponseEntity<ApiResponse<Void>> busy() {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(ApiResponse.failure("SIMULATION_BUSY", "다른 시뮬레이션이 실행 중입니다. 잠시 후 다시 시도하세요."));
  }
}
