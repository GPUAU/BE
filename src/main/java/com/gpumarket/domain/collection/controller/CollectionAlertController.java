package com.gpumarket.domain.collection.controller;

import com.gpumarket.domain.collection.dto.CollectionAlertsResponseDto;
import com.gpumarket.domain.collection.service.CollectionAlertService;
import com.gpumarket.global.api.ApiResponse;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/alerts")
public class CollectionAlertController {
  private final CollectionAlertService service;

  public CollectionAlertController(CollectionAlertService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<CollectionAlertsResponseDto> list(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) String alertType,
      @RequestParam(required = false) String handlingStatus,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor) {
    return ApiResponse.success(service.list(from, to, alertType, handlingStatus, limit, cursor));
  }
}
