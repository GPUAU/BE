package com.gpumarket.domain.collection.dto;

import java.time.Instant;
import java.util.List;

public record CollectionAlertsResponseDto(List<Alert> items, Instant asOf, String nextCursor) {
  public record Alert(
      String alertId,
      String targetId,
      String runId,
      String checkId,
      String sourceId,
      String alertType,
      String previousObservationId,
      String currentObservationId,
      Instant detectedAt,
      String ruleVersion,
      String reason,
      String handlingStatus) {}
}
