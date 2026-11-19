package com.gpumarket.domain.catalog.repository;

import com.gpumarket.domain.catalog.dto.CatalogResponseDto;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto.GpuModelDto;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto.ProviderDto;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto.RegionDto;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
  private final JdbcTemplate jdbc;

  public CatalogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean modelExists(long modelId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM gpu_model WHERE gpu_model_id = ?)",
            Boolean.class,
            modelId));
  }

  public CatalogResponseDto findCatalog() {
    List<ProviderDto> providers =
        jdbc.query(
            "SELECT provider_id, provider_name, provider_type FROM provider ORDER BY provider_id",
            (rs, row) ->
                new ProviderDto(
                    Long.toString(rs.getLong("provider_id")),
                    rs.getString("provider_name"),
                    providerType(rs.getString("provider_type"))));
    List<GpuModelDto> models =
        jdbc.query(
            "SELECT gpu_model_id, model_name, generation, release_date, release_source_url FROM"
                + " gpu_model ORDER BY gpu_model_id",
            (rs, row) ->
                new GpuModelDto(
                    Long.toString(rs.getLong("gpu_model_id")),
                    rs.getString("model_name"),
                    rs.getString("generation"),
                    rs.getObject("release_date", java.time.LocalDate.class),
                    rs.getString("release_source_url")));
    List<String> generations =
        jdbc.queryForList(
            "SELECT DISTINCT generation FROM gpu_model WHERE generation IS NOT NULL ORDER BY"
                + " generation",
            String.class);
    List<RegionDto> regions =
        jdbc.query(
            "SELECT DISTINCT provider_id, country_code, region_code FROM product ORDER BY"
                + " provider_id, country_code, region_code",
            (rs, row) ->
                new RegionDto(
                    Long.toString(rs.getLong("provider_id")),
                    rs.getString("country_code"),
                    rs.getString("region_code"),
                    null));
    return new CatalogResponseDto(
        providers,
        models,
        generations,
        regions,
        List.of("ON_DEMAND", "SPOT", "COMMITMENT", "UNKNOWN"),
        List.of("INSTANCE", "NODE", "RACK_SCALE", "UNKNOWN"),
        List.of("GENERAL_CLOUD", "GPU_SPECIALIST", "UNKNOWN"),
        List.of("STANDARD", "CONFIDENTIAL", "UNKNOWN"),
        List.of("ALL_UPFRONT", "PARTIAL_UPFRONT", "NO_UPFRONT", "NOT_APPLICABLE", "UNKNOWN"));
  }

  private String providerType(String stored) {
    return switch (stored.toLowerCase(java.util.Locale.ROOT)) {
      case "hyperscaler", "general_cloud" -> "GENERAL_CLOUD";
      case "gpu_specialist" -> "GPU_SPECIALIST";
      default -> "UNKNOWN";
    };
  }
}
