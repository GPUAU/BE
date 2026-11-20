package com.gpumarket.domain.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gpumarket.domain.agent.dto.PublicContextDto;
import com.gpumarket.domain.agent.service.AgentSessionStore.Conditions;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AgentSessionStoreTest {
  private static final Conditions CONDITIONS =
      new Conditions(new PublicContextDto("1", "ON_DEMAND", null, null, null, null), null);

  @Test
  void expiresAtThirtyMinutesEvenAfterAFollowUp() {
    var clock = mock(Clock.class);
    var start = Instant.parse("2026-11-20T00:00:00Z");
    when(clock.instant()).thenReturn(start);
    var store = new AgentSessionStore(clock);
    String id = store.create(CONDITIONS);

    when(clock.instant()).thenReturn(start.plusSeconds(1_799));
    store.complete(id, CONDITIONS);
    assertThat(store.require(id).conditions).isEqualTo(CONDITIONS);

    when(clock.instant()).thenReturn(start.plusSeconds(1_800));
    assertThatThrownBy(() -> store.require(id))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    store.delete(id);
  }

  @Test
  void cannotWriteAnInFlightLookupBackIntoADeletedSession() {
    var store = new AgentSessionStore();
    String id = store.create(CONDITIONS);
    Conditions before = store.require(id).conditions;
    store.delete(id);

    assertThatThrownBy(() -> store.complete(id, before))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
  }
}
