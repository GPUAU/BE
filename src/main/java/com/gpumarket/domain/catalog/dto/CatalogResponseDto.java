package com.gpumarket.domain.catalog.dto;

import java.time.LocalDate;
import java.util.List;

public record CatalogResponseDto(
    List<ProviderDto> providers,
    List<GpuModelDto> models,
    List<String> generations,
    List<RegionDto> regions,
    List<String> contractTypes,
    List<String> systemTypes,
    List<String> providerTypes,
    List<String> serviceOptions,
    List<String> paymentOptions) {
  public record ProviderDto(String id, String name, String type) {}

  public record GpuModelDto(
      String id, String name, String generation, LocalDate releaseDate, String releaseSourceUrl) {}

  public record RegionDto(
      String providerId, String countryCode, String regionCode, String displayName) {}
}
