package com.gpumarket.domain.catalog.controller;

import com.gpumarket.domain.catalog.dto.CatalogResponseDto;
import com.gpumarket.domain.catalog.service.CatalogService;
import com.gpumarket.global.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
  private final CatalogService service;

  public CatalogController(CatalogService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<CatalogResponseDto> getCatalog() {
    return ApiResponse.success(service.getCatalog());
  }
}
