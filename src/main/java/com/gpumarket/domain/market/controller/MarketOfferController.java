package com.gpumarket.domain.market.controller;

import com.gpumarket.domain.market.dto.ContractComparisonResponseDto;
import com.gpumarket.domain.market.service.MarketOfferService;
import com.gpumarket.global.api.ApiResponse;
import com.gpumarket.global.api.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market")
public class MarketOfferController {
  private final MarketOfferService service;

  public MarketOfferController(MarketOfferService service) {
    this.service = service;
  }

  @Operation(
      summary = "조건별 오퍼 목록",
      description = "모델·리전·계약 등으로 필터링합니다. limit 기본 50, 최대 100. 다음 페이지는 cursor를 사용합니다.")
  @GetMapping("/offers")
  public ApiResponse<Map<String, Object>> offers(
      @RequestParam MultiValueMap<String, String> params) {
    return ApiResponse.success(service.list(query(params)));
  }

  @Operation(summary = "오퍼 상세와 가격 근거")
  @GetMapping("/offers/{offerId}")
  public ApiResponse<Map<String, Object>> offer(
      @PathVariable long offerId, @RequestParam Map<String, String> params) {
    return ApiResponse.success(service.detail(offerId, params));
  }

  @Operation(summary = "동일 조건 시장 요약", description = "modelId 필수. 동일 리전·구성·계약의 최신 확정가와 표본 수를 제공합니다.")
  @GetMapping("/summaries")
  public ApiResponse<Map<String, Object>> summary(
      @RequestParam MultiValueMap<String, String> params) {
    return ApiResponse.success(service.summary(query(params)));
  }

  @Operation(
      summary = "같은 상품의 공개 계약 가격 비교",
      parameters =
          @Parameter(name = "currency", in = ParameterIn.QUERY, description = "USD 또는 KRW"))
  @GetMapping("/products/{productId}/contracts")
  public ApiResponse<ContractComparisonResponseDto> contracts(
      @PathVariable long productId,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
    return ApiResponse.success(service.contracts(productId, query(params)));
  }

  private static Map<String, String> query(MultiValueMap<String, String> params) {
    var result = new LinkedHashMap<String, String>();
    params.forEach(
        (name, values) -> {
          if (name.equals("providerIds")) {
            result.put(name, String.join(",", values));
          } else if (values.size() == 1) {
            result.put(name, values.get(0));
          } else {
            throw new InvalidRequestException(name + " 조건은 한 번만 지정할 수 있습니다.");
          }
        });
    return result;
  }
}
