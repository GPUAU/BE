package com.gpumarket.domain.simulation.controller;

import com.gpumarket.domain.simulation.dto.DataCenterSimulationRequest;
import com.gpumarket.domain.simulation.service.DataCenterSimulationService;
import com.gpumarket.global.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DataCenterSimulationController {
  private final DataCenterSimulationService service;

  public DataCenterSimulationController(DataCenterSimulationService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/data-center-simulations")
  public ApiResponse<Map<String, Object>> simulate(
      @Valid @RequestBody DataCenterSimulationRequest input) {
    return ApiResponse.success(service.simulate(input));
  }
}
