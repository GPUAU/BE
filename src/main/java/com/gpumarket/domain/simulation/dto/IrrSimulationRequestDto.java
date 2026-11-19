package com.gpumarket.domain.simulation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.CostItem;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ExactInteger;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.InterestRateType;
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
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record IrrSimulationRequestDto(
    @NotNull @Valid CostInput costInput,
    @Positive @Digits(integer = 16, fraction = 8) BigDecimal investment,
    @JsonDeserialize(using = ExactInteger.class) @Positive @Max(100) Integer analysisYears,
    @NotNull @PositiveOrZero @Digits(integer = 16, fraction = 8) BigDecimal residualValue,
    @NotNull @DecimalMin(value = "-1", inclusive = false) @Digits(integer = 4, fraction = 8)
        BigDecimal targetIrr,
    @NotNull @Size(max = 50)
        List<@NotNull @PositiveOrZero @Digits(integer = 16, fraction = 12) BigDecimal>
            candidatePrices,
    @NotNull MarketConstraint marketConstraint,
    @Valid MarketFilters marketFilters) {

  public enum MarketConstraint {
    NONE,
    MINIMUM,
    MEDIAN,
    MAXIMUM;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MarketConstraint parse(JsonNode value) {
      if (!value.isTextual()) {
        throw new IllegalArgumentException("시장 제약을 이름으로 입력하세요.");
      }
      return valueOf(value.textValue());
    }
  }

  public record CostInput(
      @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String modelId,
      @NotNull @Size(min = 1, max = 11) List<@NotNull @Valid CostItem> items,
      @NotNull @Valid CostAssumptions assumptions,
      @Positive @Digits(integer = 12, fraction = 8) BigDecimal inputFxKrwPerUsd) {
    @JsonAnySetter
    void unsupported(String name, JsonNode value) {
      rejectUnknown();
    }
  }

  public record CostAssumptions(
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Positive Integer gpuCount,
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Positive @Max(100)
          Integer depreciationYears,
      @NotNull @Positive @DecimalMax("1") @Digits(integer = 1, fraction = 8) BigDecimal utilization,
      @PositiveOrZero @Digits(integer = 2, fraction = 8) BigDecimal annualInterestRate,
      @NotNull InterestRateType interestRateType) {
    @JsonAnySetter
    void unsupported(String name, JsonNode value) {
      rejectUnknown();
    }
  }

  @JsonAnySetter
  void unsupported(String name, JsonNode value) {
    rejectUnknown();
  }

  private static void rejectUnknown() {
    throw new IllegalArgumentException("지원하지 않는 요청 필드입니다.");
  }
}
