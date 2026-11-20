package com.gpumarket.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gpumarket.domain.agent.dto.AgentMessageRequestDto;
import com.gpumarket.domain.agent.dto.AgentMessageResponseDto;
import com.gpumarket.domain.agent.dto.PublicContextDto;
import com.gpumarket.domain.agent.service.AgentMessageService;
import com.gpumarket.domain.agent.service.AgentMessageService.SensitiveInput;
import com.gpumarket.domain.agent.service.AgentSessionStore;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto.GpuModelDto;
import com.gpumarket.domain.catalog.dto.CatalogResponseDto.ProviderDto;
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
  private MarketOfferService offers;

  @BeforeEach
  void initialize() {
    var catalog = mock(CatalogService.class);
    offers = mock(MarketOfferService.class);
    var models =
        List.of(
            new GpuModelDto("1", "B300", null, null, null),
            new GpuModelDto("2", "GB300", null, null, null),
            new GpuModelDto("3", "GB300 NVL72", null, null, null));
    when(catalog.getCatalog())
        .thenReturn(
            new CatalogResponseDto(
                List.of(new ProviderDto("7", "AWS", "GENERAL_CLOUD")),
                models,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    var empty = new PublicPriceLookupDto(List.of(), Instant.EPOCH, false);
    when(offers.publicPrices(anyMap())).thenReturn(empty);
    service = new AgentMessageService(catalog, offers, new AgentSessionStore());
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
  void followUpChangesOnlyTheCountryAndKeepsTheModelAndContract() {
    var first = answer("B300 미국 온디맨드 가격");

    var next = service.answer(new AgentMessageRequestDto(first.sessionId(), "그럼 독일은요?", null));

    assertThat(next.appliedConditions().countryCode()).isEqualTo("DE");
    assertThat(next.retainedConditions()).containsExactly("modelId", "contractType");
    assertThat(next.changedConditions()).containsExactly("countryCode");
  }

  @Test
  void modelOnlyReplyFinishesClarificationWithTheCountryFromTheFirstTurn() {
    var first = answer("미국 가격 알려줘");
    assertThat(first.clarificationFields()).containsExactly("modelId");
    assertThat(first.assumptions()).anyMatch(value -> value.contains("온디맨드"));

    var next = service.answer(new AgentMessageRequestDto(first.sessionId(), "B300", null));

    assertThat(next.sampleStatus()).isEqualTo("QUERIED");
    assertThat(next.assumptions()).anyMatch(value -> value.contains("온디맨드"));
    assertThat(next.retainedConditions()).contains("countryCode");
    verify(offers)
        .publicPrices(Map.of("modelId", "1", "contractType", "ON_DEMAND", "countryCode", "US"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"그중 GPU 특화 사업자만", "GPU 특화 사업자 가격은?"})
  void followUpChangesProviderTypeAndDropsAnIncompatibleNamedProvider(String message) {
    var first = answer("B300 AWS 미국 온디맨드 가격");

    var next = service.answer(new AgentMessageRequestDto(first.sessionId(), message, null));

    assertThat(next.changedConditions()).contains("providerType", "providerIds");
    verify(offers)
        .publicPrices(
            Map.of(
                "modelId",
                "1",
                "contractType",
                "ON_DEMAND",
                "countryCode",
                "US",
                "providerType",
                "GPU_SPECIALIST"));
  }

  @Test
  void explicitCompatibleProviderTypeDoesNotDropTheNamedProvider() {
    var first = answer("B300 AWS 미국 온디맨드 가격");
    var context = new PublicContextDto(null, null, null, null, null, "GENERAL_CLOUD");

    var next = service.answer(new AgentMessageRequestDto(first.sessionId(), "이 조건으로요", context));

    assertThat(next.retainedConditions()).contains("providerIds");
    verify(offers)
        .publicPrices(
            Map.of(
                "modelId",
                "1",
                "contractType",
                "ON_DEMAND",
                "countryCode",
                "US",
                "providerType",
                "GENERAL_CLOUD",
                "providerIds",
                "7"));
  }

  @Test
  void unrelatedQuestionWithACountryDoesNotQueryOrOverwriteSessionConditions() {
    var first = answer("B300 독일 온디맨드 가격");

    var unrelated =
        service.answer(new AgentMessageRequestDto(first.sessionId(), "미국 수도는 어디야?", null));

    assertThat(unrelated.sampleStatus()).isEqualTo("NOT_QUERIED");
    verify(offers)
        .publicPrices(Map.of("modelId", "1", "contractType", "ON_DEMAND", "countryCode", "DE"));
    verifyNoMoreInteractions(offers);
    var next = service.answer(new AgentMessageRequestDto(first.sessionId(), "가격 다시 알려줘", null));
    assertThat(next.appliedConditions().countryCode()).isEqualTo("DE");
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
