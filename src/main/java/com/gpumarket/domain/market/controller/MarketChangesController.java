package com.gpumarket.domain.market.controller;

import com.gpumarket.domain.market.dto.MarketChangesRequestDto;
import com.gpumarket.domain.market.dto.MarketChangesResponseDto;
import com.gpumarket.domain.market.service.MarketChangesService;
import com.gpumarket.global.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MarketChangesController {
  private final MarketChangesService service;

  public MarketChangesController(MarketChangesService service) {
    this.service = service;
  }

  @Operation(
      summary = "동일 오퍼의 가격·상태 변화 조회",
      parameters = {
        @Parameter(name = "modelId", in = ParameterIn.QUERY, required = true, example = "1"),
        @Parameter(
            name = "from",
            in = ParameterIn.QUERY,
            required = true,
            example = "2026-11-16T00:00:00Z"),
        @Parameter(
            name = "to",
            in = ParameterIn.QUERY,
            required = true,
            example = "2026-11-19T00:00:00Z"),
        @Parameter(name = "providerIds", in = ParameterIn.QUERY),
        @Parameter(name = "countryCode", in = ParameterIn.QUERY),
        @Parameter(name = "regionCode", in = ParameterIn.QUERY),
        @Parameter(name = "contractType", in = ParameterIn.QUERY),
        @Parameter(name = "includeKrwDecomposition", in = ParameterIn.QUERY),
        @Parameter(name = "limit", in = ParameterIn.QUERY),
        @Parameter(name = "cursor", in = ParameterIn.QUERY)
      })
  @GetMapping("/api/v1/market/changes")
  public ApiResponse<MarketChangesResponseDto> changes(
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
    return ApiResponse.success(service.list(MarketChangesRequestDto.parse(params)));
  }
}
