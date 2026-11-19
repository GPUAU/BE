package com.gpumarket.domain.simulation.controller;

import com.gpumarket.domain.simulation.service.CostSimulationService.CostInputException;
import com.gpumarket.global.api.ApiResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    assignableTypes = {CostSimulationController.class, IrrSimulationController.class})
class SimulationInputExceptionHandler {
  @ExceptionHandler(CostInputException.class)
  ResponseEntity<ApiResponse<Void>> invalidCost(CostInputException exception) {
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
