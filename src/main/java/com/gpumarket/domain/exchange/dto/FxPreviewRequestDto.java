package com.gpumarket.domain.exchange.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record FxPreviewRequestDto(
    @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Input> items,
    @NotBlank @Pattern(regexp = "NOMINAL|EFFECTIVE") String priceType,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
        BigDecimal krwPerUsd) {
  public record Input(
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") @DecimalMax("9223372036854775807")
          String observationId,
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") @DecimalMax("9223372036854775807")
          String normalizationId) {}
}
