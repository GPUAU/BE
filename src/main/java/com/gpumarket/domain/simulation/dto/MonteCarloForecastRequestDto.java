package com.gpumarket.domain.simulation.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gpumarket.domain.simulation.dto.CostSimulationRequestDto.ExactInteger;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig.Simulation;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloJson.StrictFields;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MonteCarloForecastRequestDto(
    @Valid @NotNull Simulation simulation,
    @JsonDeserialize(using = ExactInteger.class) @NotNull @Min(12) @Max(60) Integer analysisMonths,
    @Valid
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        ObservedHistorySource historySource)
    implements StrictFields {}
