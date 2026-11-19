package com.gpumarket.domain.simulation.controller;

import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto;
import com.gpumarket.domain.simulation.dto.CostSimulationResponseDto;
import com.gpumarket.domain.simulation.service.CostSimulationService;
import com.gpumarket.domain.simulation.service.CostSimulationService.CostInputException;
import com.gpumarket.global.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CostSimulationController {
  private final CostSimulationService service;

  public CostSimulationController(CostSimulationService service) {
    this.service = service;
  }

  @Operation(summary = "원가·후보 판매가격 시뮬레이션")
  @PostMapping("/api/v1/cost-simulations")
  public ApiResponse<CostSimulationResponseDto> simulate(
      @Valid @RequestBody CostSimulationRequestDto request) {
    return ApiResponse.success(service.simulate(request));
  }

  @ExceptionHandler(CostInputException.class)
  public ResponseEntity<ApiResponse<Void>> invalidCost(CostInputException exception) {
    var error = new ApiResponse.ErrorDetail(exception.field(), exception.getMessage());
    var common = ApiResponse.validationFailure(List.of(error));
    return ResponseEntity.unprocessableEntity()
        .body(
            new ApiResponse<>(
                false,
                exception.code(),
                exception.getMessage(),
                null,
                common.meta(),
                common.errors()));
  }
}
