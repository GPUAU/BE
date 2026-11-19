package com.gpumarket.domain.collection.controller;

import com.gpumarket.domain.collection.dto.CollectionRunsRequestDto;
import com.gpumarket.domain.collection.dto.CollectionRunsResponseDto;
import com.gpumarket.domain.collection.service.CollectionRunService;
import com.gpumarket.global.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectionRunController {
  private final CollectionRunService service;

  public CollectionRunController(CollectionRunService service) {
    this.service = service;
  }

  @Operation(
      summary = "수집 실행 이력과 대상별 결과 조회",
      parameters = {
        @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-11-18T00:00:00Z"),
        @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-11-19T00:00:00Z"),
        @Parameter(name = "sourceId", in = ParameterIn.QUERY),
        @Parameter(name = "status", in = ParameterIn.QUERY, example = "PARTIAL_FAILURE"),
        @Parameter(name = "limit", in = ParameterIn.QUERY),
        @Parameter(name = "cursor", in = ParameterIn.QUERY)
      })
  @GetMapping("/api/v1/operations/collection-runs")
  public ApiResponse<CollectionRunsResponseDto> runs(
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
    return ApiResponse.success(service.list(CollectionRunsRequestDto.parse(params)));
  }
}
