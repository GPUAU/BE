package com.gpumarket.domain.simulation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CostSimulationRequestDto(
    @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String modelId,
    @NotNull @Size(min = 1, max = 11) List<@NotNull @Valid CostItem> items,
    @NotNull @Valid Assumptions assumptions,
    @NotNull @Valid PricingPolicy pricing,
    @Positive @Digits(integer = 12, fraction = 8) BigDecimal inputFxKrwPerUsd,
    @Valid MarketFilters marketFilters,
    @Valid Electricity electricity) {
  public enum ItemType {
    ACQUISITION,
    GPU,
    MEMORY,
    NETWORK_STORAGE,
    RACK_FACILITY,
    RENT,
    OPERATIONS,
    FINANCING,
    ELECTRICITY,
    OTHER
  }

  public enum ItemStatus {
    PUBLIC_SOURCE,
    USER_INPUT,
    NOT_INCLUDED,
    INCLUDED_IN_OTHER
  }

  public enum Currency {
    USD,
    KRW
  }

  public enum Period {
    ONE_TIME,
    MONTH,
    YEAR
  }

  public enum InterestRateType {
    PUBLIC_BASE,
    ACTUAL_LOAN,
    ASSUMED,
    NOT_APPLIED
  }

  public enum PricingMethod {
    REVENUE_MARGIN,
    COST_MARKUP
  }

  public record CostItem(
      @NotNull ItemType itemType,
      @NotNull ItemStatus status,
      @PositiveOrZero @Digits(integer = 16, fraction = 8) BigDecimal amount,
      Currency currency,
      Period period,
      @Size(max = 100) String region,
      LocalDate referenceDate,
      @Size(max = 2048) String sourceUrl,
      ItemType includedInItemType,
      @JsonDeserialize(using = ExactInteger.class) @Positive @Max(100) Integer usefulLifeYears,
      @PositiveOrZero @Digits(integer = 16, fraction = 8) BigDecimal residualValueUsd) {
    @JsonAnySetter
    void unsupported(String name, JsonNode value) {
      rejectUnknown();
    }
  }

  public record Electricity(
      @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 8) BigDecimal itPowerKw,
      @NotNull @DecimalMin("1") @Digits(integer = 4, fraction = 8) BigDecimal pue,
      @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 8) BigDecimal krwPerKwh) {
    @JsonAnySetter
    void unsupported(String name, JsonNode value) {
      rejectUnknown();
    }
  }

  public record Assumptions(
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Positive Integer gpuCount,
      @JsonDeserialize(using = ExactInteger.class) @NotNull @Positive @Max(100)
          Integer depreciationYears,
      @NotNull @Positive @DecimalMax("1") @Digits(integer = 1, fraction = 8) BigDecimal utilization,
      @PositiveOrZero @Digits(integer = 2, fraction = 8) BigDecimal annualInterestRate,
      @NotNull InterestRateType interestRateType,
      @PositiveOrZero @Digits(integer = 16, fraction = 8) BigDecimal residualValueUsd,
      @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String calendarMonth,
      @Pattern(regexp = "SCREEN_SCENARIO") String costProfile) {
    @JsonAnySetter
    void unsupported(String name, JsonNode value) {
      rejectUnknown();
    }
  }

  public record PricingPolicy(
      @NotNull PricingMethod method,
      @NotNull @Digits(integer = 4, fraction = 8) BigDecimal targetRate) {
    @JsonAnySetter
    void unsupported(String name, JsonNode value) {
      rejectUnknown();
    }
  }

  public record MarketFilters(
      @Pattern(regexp = "[1-9][0-9]{0,18}") String modelId,
      @Size(max = 100) List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> providerIds,
      @Pattern(regexp = "GENERAL_CLOUD|GPU_SPECIALIST|UNKNOWN|ALL") String providerType,
      @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
      @Size(max = 100) String regionCode,
      @Pattern(regexp = "INSTANCE|NODE|RACK_SCALE") String systemType,
      @Pattern(regexp = "STANDARD|CONFIDENTIAL") String serviceOption,
      @JsonDeserialize(using = ExactInteger.class) @Positive Integer gpuCount,
      @Pattern(regexp = "ON_DEMAND|SPOT|COMMITMENT") String contractType,
      @JsonDeserialize(using = ExactInteger.class) @Positive Integer commitmentMonths,
      @Size(max = 200) String contractProductType,
      @Size(max = 200) String intraNodeInterconnect,
      @Pattern(regexp = "ALL_UPFRONT|PARTIAL_UPFRONT|NO_UPFRONT|NOT_APPLICABLE")
          String paymentOption) {
    @JsonAnySetter
    void unsupported(String name, JsonNode value) {
      rejectUnknown();
    }
  }

  @JsonAnySetter
  void unsupported(String name, JsonNode value) {
    rejectUnknown();
  }

  public static class ExactInteger extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
        return (Integer) context.handleUnexpectedToken(Integer.class, parser);
      }
      return parser.getIntValue();
    }
  }

  private static void rejectUnknown() {
    throw new IllegalArgumentException("지원하지 않는 요청 필드입니다.");
  }
}
