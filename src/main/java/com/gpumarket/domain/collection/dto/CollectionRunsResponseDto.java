package com.gpumarket.domain.collection.dto;

import java.time.Instant;
import java.util.List;

public record CollectionRunsResponseDto(
    List<CollectionRunDto> items, Instant asOf, String nextCursor) {
  public record CollectionRunDto(
      String runId,
      Instant startedAt,
      Instant finishedAt,
      String status,
      String collectorVersion,
      List<CollectionTargetResultDto> targets) {}

  public record CollectionTargetResultDto(
      String targetId,
      String sourceId,
      String scopeDescription,
      String processingStatus,
      Instant checkedAt,
      String reason,
      Instant lastSuccessAt) {}
}
