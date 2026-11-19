package com.gpumarket.domain.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gpumarket.domain.exchange.collector.KoreaEximCollector;
import org.junit.jupiter.api.Test;

class KoreaEximCollectorTest {
  @Test
  void extractsUsdBaseRateAndRemovesThousandsSeparator() {
    assertThat(
            KoreaEximCollector.parseRate(
                "[{\"result\":1,\"cur_unit\":\"USD\",\"deal_bas_r\":\"1,338.8\"}]"))
        .hasValueSatisfying(rate -> assertThat(rate).isEqualByComparingTo("1338.8"));
  }

  @Test
  void emptyResponseMeansNoPublishedRate() {
    assertThat(KoreaEximCollector.parseRate("[]")).isEmpty();
  }

  @Test
  void errorOrInvalidRateIsNotSaved() {
    assertThatThrownBy(() -> KoreaEximCollector.parseRate("[{\"result\":2}] "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                KoreaEximCollector.parseRate(
                    "[{\"result\":1,\"cur_unit\":\"USD\",\"deal_bas_r\":\"0\"}] "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
