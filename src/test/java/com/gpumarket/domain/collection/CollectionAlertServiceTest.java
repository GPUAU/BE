package com.gpumarket.domain.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gpumarket.domain.collection.repository.CollectionAlertRepository;
import com.gpumarket.domain.collection.service.CollectionAlertService;
import com.gpumarket.global.api.InvalidRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CollectionAlertServiceTest {
  @Test
  void paginatesAndPreservesFilterAndSnapshot() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:alerts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("DROP ALL OBJECTS");
    jdbc.execute(
        "CREATE TABLE collection_alert (alert_id BIGINT, target_id BIGINT, run_id BIGINT, check_id"
            + " BIGINT, source_id BIGINT, alert_type TEXT, previous_observation_id BIGINT,"
            + " current_observation_id BIGINT, detected_at TIMESTAMP WITH TIME ZONE, rule_version"
            + " TEXT, reason TEXT, handling_status TEXT)");
    jdbc.update(
        "INSERT INTO collection_alert VALUES"
            + " (1,NULL,NULL,NULL,NULL,'collection_failed',NULL,NULL,TIMESTAMP WITH TIME ZONE"
            + " '2026-11-17 00:00:00+00',NULL,NULL,'open'),"
            + " (2,NULL,NULL,NULL,NULL,'collection_failed',NULL,NULL,TIMESTAMP WITH TIME ZONE"
            + " '2026-11-17 00:00:00+00',NULL,NULL,'open')");
    var service =
        new CollectionAlertService(
            new CollectionAlertRepository(jdbc),
            Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC));
    var first = service.list(null, null, "COLLECTION_FAILED", null, 1, null);
    assertThat(first.items()).extracting("alertId").containsExactly("1");
    assertThat(first.nextCursor()).isNotBlank();
    var second = service.list(null, null, "COLLECTION_FAILED", null, 1, first.nextCursor());
    assertThat(second.items()).extracting("alertId").containsExactly("2");
    assertThat(second.asOf()).isEqualTo(first.asOf());
    assertThat(second.nextCursor()).isNull();
    assertThatThrownBy(() -> service.list(null, null, "PRICE_SPIKE", null, 1, first.nextCursor()))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void rejectsInvalidRangesAndCursors() {
    var service = new CollectionAlertService(null, Clock.systemUTC());
    assertThatThrownBy(
            () ->
                service.list(
                    Instant.parse("2026-11-19T00:00:00Z"),
                    Instant.parse("2026-11-18T00:00:00Z"),
                    null,
                    null,
                    50,
                    null))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.list(null, null, null, null, 50, "not-a-cursor"))
        .isInstanceOf(InvalidRequestException.class);
  }
}
