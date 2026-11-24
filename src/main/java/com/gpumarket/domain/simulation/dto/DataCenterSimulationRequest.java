package com.gpumarket.domain.simulation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ExactInteger;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.MarketFilters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record DataCenterSimulationRequest(
    @NotNull @Positive @Max(100000000) @JsonDeserialize(using = ExactInteger.class)
        Integer gpuCount,
    @NotNull @Positive @Max(10000) @JsonDeserialize(using = ExactInteger.class) Integer gpusPerRack,
    @NotNull @Positive @Digits(integer = 6, fraction = 8) BigDecimal rackPowerKw,
    @NotNull @DecimalMin("1") @DecimalMax("10") BigDecimal pue,
    @NotNull @Positive @DecimalMax("1") BigDecimal utilization,
    @NotNull @PositiveOrZero @DecimalMax("1") BigDecimal powerLoadFraction,
    @NotNull @PositiveOrZero @Digits(integer = 4, fraction = 8) BigDecimal electricityUsdPerKwh,
    @NotNull @PositiveOrZero @Digits(integer = 16, fraction = 8) BigDecimal otherAnnualOpexUsd,
    @NotNull CapitalMethod capitalMethod,
    @NotNull @PositiveOrZero @DecimalMax("1") BigDecimal annualDiscountRate,
    @NotNull @Valid Asset server,
    @NotNull @Valid Asset network,
    @NotNull @Valid Asset facility,
    @NotNull @PositiveOrZero @DecimalMax(value = "1", inclusive = false) BigDecimal targetMargin,
    @NotNull @PositiveOrZero @Digits(integer = 8, fraction = 8) BigDecimal bareMetalPrice,
    @NotNull @PositiveOrZero @DecimalMax("10") BigDecimal softwareAttach,
    @Pattern(regexp = "[1-9][0-9]{0,18}") String modelId,
    @Valid MarketFilters marketFilters) {
  public enum CapitalMethod {
    REPORT_FACTORS,
    DISCOUNT_RATE
  }

  public record Asset(
      @NotNull @PositiveOrZero @Digits(integer = 16, fraction = 8) BigDecimal capexUsd,
      @NotNull @Positive @Max(100) @JsonDeserialize(using = ExactInteger.class) Integer years,
      @NotNull @PositiveOrZero @Digits(integer = 16, fraction = 8) BigDecimal residualUsd) {
    @JsonAnySetter
    void unknown(String key, JsonNode value) {
      throw new IllegalArgumentException("지원하지 않는 자산 필드입니다.");
    }
  }

  @JsonAnySetter
  void unknown(String key, JsonNode value) {
    throw new IllegalArgumentException("지원하지 않는 데이터센터 필드입니다.");
  }
}
