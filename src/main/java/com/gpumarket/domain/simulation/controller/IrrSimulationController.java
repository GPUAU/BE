package com.gpumarket.domain.simulation.controller;

import com.gpumarket.domain.simulation.dto.IrrSimulationRequestDto;
import com.gpumarket.domain.simulation.dto.IrrSimulationResponseDto;
import com.gpumarket.domain.simulation.service.IrrSimulationService;
import com.gpumarket.global.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IrrSimulationController {
  private final IrrSimulationService service;

  public IrrSimulationController(IrrSimulationService service) {
    this.service = service;
  }

  @Operation(summary = "현금흐름·IRR 및 목표 판매가격 시뮬레이션")
  @PostMapping("/api/v1/irr-simulations")
  public ApiResponse<IrrSimulationResponseDto> simulate(
      @Valid @RequestBody IrrSimulationRequestDto request) {
    return ApiResponse.success(service.simulate(request));
  }
}
