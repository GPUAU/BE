package com.gpumarket.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gpumarket.domain.agent.dto.AgentMessageRequestDto;
import com.gpumarket.domain.agent.dto.AgentMessageResponseDto;
import com.gpumarket.domain.agent.dto.PublicContextDto;
import com.gpumarket.domain.agent.service.AgentMessageService;
import com.gpumarket.domain.agent.service.AgentMessageService.SensitiveInput;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto.GpuModelDto;
import com.gpumarket.domain.catalog.service.CatalogService;
import com.gpumarket.domain.market.dto.PublicPriceLookupDto;
import com.gpumarket.domain.market.service.MarketOfferService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentMessageServiceTest {
  private AgentMessageService service;

  @BeforeEach
  void initialize() {
    var catalog = mock(CatalogService.class);
    var offers = mock(MarketOfferService.class);
    var models =
        List.of(
            new GpuModelDto("1", "B300", null, null, null),
            new GpuModelDto("2", "GB300", null, null, null),
            new GpuModelDto("3", "GB300 NVL72", null, null, null));
    when(catalog.getCatalog())
        .thenReturn(
            new CatalogResponseDto(
                List.of(), models, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of()));
    var empty = new PublicPriceLookupDto(List.of(), Instant.EPOCH, false);
    when(offers.publicPrices(Map.of("modelId", "1", "contractType", "ON_DEMAND")))
        .thenReturn(empty);
    when(offers.publicPrices(
            Map.of("modelId", "1", "contractType", "ON_DEMAND", "countryCode", "US")))
        .thenReturn(empty);
    when(offers.publicPrices(
            Map.of("modelId", "1", "contractType", "ON_DEMAND", "countryCode", "DE")))
        .thenReturn(empty);
    when(offers.publicPrices(Map.of("modelId", "3", "contractType", "ON_DEMAND")))
        .thenReturn(empty);
    service = new AgentMessageService(catalog, offers);
  }

  @Test
  void readsFullModelNameAndRecognizesSeparatelyMentionedModels() {
    assertThat(answer("GB300 NVL72 온디맨드 가격").appliedConditions().modelId()).isEqualTo("3");
    assertThat(answer("GB300 NVL72와 GB300 온디맨드 가격").clarificationFields())
        .containsExactly("modelId");
  }

  @Test
  void recognizesCountryNamesWithoutTreatingPronounsAsCountries() {
    assertThat(
            answer("Can you tell us the B300 on-demand price?").appliedConditions().countryCode())
        .isNull();
    assertThat(answer("B300 on-demand price in Germany").appliedConditions().countryCode())
        .isEqualTo("DE");
    var us = new PublicContextDto(null, null, "US", null, null, null);
    var conflicting = service.answer(new AgentMessageRequestDto(null, "B300 독일 온디맨드 가격", us));
    assertThat(conflicting.clarificationFields()).containsExactly("countryCode");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "What will the B300 on-demand price be tomorrow?",
        "B300 가격을 유로로 알려줘",
        "2025년 11월 B300 온디맨드 가격"
      })
  void clarifiesUnsupportedTimeOrCurrency(String message) {
    assertThat(answer(message).status()).isEqualTo("NEEDS_CLARIFICATION");
  }

  @Test
  void treatsPerOneGpuAsAUnitInsteadOfAnOfferConfiguration() {
    assertThat(answer("B300 on-demand price per 1 GPU").appliedConditions().gpuCount()).isNull();
  }

  @Test
  void blocksInternalPurchasePriceQuestion() {
    assertThatThrownBy(() -> answer("B300 internal purchase price"))
        .isInstanceOf(SensitiveInput.class);
  }

  private AgentMessageResponseDto answer(String message) {
    return service.answer(new AgentMessageRequestDto(null, message, null));
  }
}
