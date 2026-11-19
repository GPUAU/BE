package com.gpumarket.domain.market.controller;

import com.gpumarket.domain.market.dto.MarketAvailabilityRequestDto;
import com.gpumarket.domain.market.dto.MarketAvailabilityResponseDto;
import com.gpumarket.domain.market.service.MarketAvailabilityService;
import com.gpumarket.global.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MarketAvailabilityController {
  private final MarketAvailabilityService service;

  public MarketAvailabilityController(MarketAvailabilityService service) {
    this.service = service;
  }

  @Operation(
      summary = "모델·사업자별 가격 확보 상태 조회",
      parameters = {
        @Parameter(name = "modelId", in = ParameterIn.QUERY, required = true, example = "2"),
        @Parameter(name = "providerIds", in = ParameterIn.QUERY),
        @Parameter(name = "countryCode", in = ParameterIn.QUERY),
        @Parameter(name = "contractType", in = ParameterIn.QUERY),
        @Parameter(name = "limit", in = ParameterIn.QUERY),
        @Parameter(name = "cursor", in = ParameterIn.QUERY)
      })
  @GetMapping("/api/v1/market/availability-records")
  public ApiResponse<MarketAvailabilityResponseDto> availability(
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
    return ApiResponse.success(service.list(MarketAvailabilityRequestDto.parse(params)));
  }
}
