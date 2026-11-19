package com.gpumarket.domain.market;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gpumarket.domain.market.controller.MarketHistoryController;
import com.gpumarket.global.api.InvalidRequestException;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MarketHistoryLimitTest {
  @Test
  void rejectsOversizedHistoryBeforeFetchingEvidence() {
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(Collections.nCopies(12_001, Map.of("check_id", 1L)));
    var controller = new MarketHistoryController(jdbc);

    assertThatThrownBy(
            () ->
                controller.history(
                    Map.of(
                        "modelId",
                        "1",
                        "from",
                        "2026-10-01T00:00:00Z",
                        "to",
                        "2026-10-02T00:00:00Z")))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("12,000");

    verify(jdbc).queryForList(anyString(), anyMap());
    verifyNoMoreInteractions(jdbc);
  }
}
