package com.gpumarket.domain.simulation.dto;

import com.gpumarket.domain.simulation.montecarlo.MonteCarloJson.StrictFields;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ObservedHistorySource(
    @NotNull @Positive Long modelId, @NotNull @Positive Long offerId) implements StrictFields {}
