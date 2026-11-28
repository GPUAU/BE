package com.gpumarket.domain.interest.controller;

import com.gpumarket.domain.interest.dto.LatestInterestRateResponseDto;
import com.gpumarket.domain.interest.service.InterestRateService;
import com.gpumarket.global.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class InterestRateController {
  private final InterestRateService service;

  public InterestRateController(InterestRateService service) {
    this.service = service;
  }

  /** 원가 계산 금리 가정의 제안값: 한국은행 ECOS 회사채(3년, AA-) 최신 공시 금리. */
  @GetMapping("/interest-rates/latest")
  public ApiResponse<LatestInterestRateResponseDto> latest() {
    return ApiResponse.success(service.latest());
  }
}
