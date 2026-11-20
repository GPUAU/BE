package com.gpumarket.domain.simulation.dto;

import com.gpumarket.domain.simulation.montecarlo.MonteCarloConfig;
import com.gpumarket.domain.simulation.montecarlo.MonteCarloJson.StrictFields;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record MonteCarloPricingRequestDto(
    @Valid @NotNull MonteCarloConfig config, @Valid @NotNull MonteCarloConfig.Selection selection)
    implements StrictFields {}
