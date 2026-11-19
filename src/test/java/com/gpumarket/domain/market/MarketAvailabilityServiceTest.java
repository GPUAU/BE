package com.gpumarket.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gpumarket.domain.market.dto.MarketAvailabilityRequestDto;
import com.gpumarket.domain.market.repository.MarketAvailabilityRepository;
import com.gpumarket.domain.market.repository.MarketOfferRepository;
import com.gpumarket.domain.market.service.MarketAvailabilityService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketAvailabilityServiceTest {
  @Test
  void appliesTheSameEvidenceCutoffToDisclosureCheckAndLastSuccessfulPrice() {
    var repository = mock(MarketAvailabilityRepository.class);
    var offers = mock(MarketOfferRepository.class);
    var service = new MarketAvailabilityService(repository, offers);
    Instant old = Instant.now().minus(3, ChronoUnit.DAYS);
    Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
    when(repository.find(anyMap()))
        .thenReturn(
            List.of(
                Map.ofEntries(
                    Map.entry("target_id", 1L),
                    Map.entry("observation_id", 100L),
                    Map.entry("observation_check_id", 10L),
                    Map.entry("check_id", 11L),
                    Map.entry("disclosure_check_id", 10L),
                    Map.entry("disclosure_status", "public"),
                    Map.entry("processing_status", "collection_failed"),
                    Map.entry("confirmed_at", old),
                    Map.entry("collected_at", old),
                    Map.entry("result_status", "success"),
                    Map.entry("effective_usd_gpu_hour", BigDecimal.TEN))));
    when(offers.evidence(anyList()))
        .thenReturn(
            List.of(
                Map.of(
                    "check_id",
                    10L,
                    "evidence_id",
                    1L,
                    "captured_at",
                    old,
                    "primary_verified",
                    true,
                    "verified_at",
                    future),
                Map.of("check_id", 10L, "evidence_id", 2L, "captured_at", future),
                Map.of("check_id", 11L, "evidence_id", 3L, "captured_at", future)));

    var result = service.list(new MarketAvailabilityRequestDto(2, List.of(), null, null, 50, null));
    var item = result.items().get(0);

    assertThat(item.latestCheck().processingStatus()).isEqualTo("COLLECTION_FAILED");
    assertThat(item.freshness().status()).isEqualTo("STALE");
    assertThat(item.evidence()).isEmpty();
    assertThat(item.latestDisclosure().evidence()).hasSize(1);
    assertThat(item.latestDisclosure().evidence().get(0))
        .containsEntry("evidenceId", "1")
        .containsEntry("primaryVerified", null)
        .containsEntry("verifiedAt", null);
    assertThat(item.lastSuccessfulObservation().get("evidence"))
        .isEqualTo(item.latestDisclosure().evidence());
  }
}
