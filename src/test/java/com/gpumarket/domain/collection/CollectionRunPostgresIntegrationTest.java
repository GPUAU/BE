package com.gpumarket.domain.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpumarket.domain.collection.dto.CollectionRunsRequestDto;
import com.gpumarket.domain.collection.repository.CollectionRunRepository;
import com.gpumarket.domain.collection.repository.CollectionRunRepository.Run;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class CollectionRunPostgresIntegrationTest {
  @Test
  void filtersRunsAndKeepsTargetOutcomeAndHistoricalSuccessSeparate() {
    var ds =
        new SingleConnectionDataSource(
            System.getenv("TEST_POSTGRES_URL"), System.getenv("TEST_POSTGRES_USER"), "", true);
    try {
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute(
          """
          CREATE TEMP TABLE collection_run(run_id bigint, started_at timestamptz,
            finished_at timestamptz, run_status text, collector_version text)
          """);
      jdbc.execute(
          """
CREATE TEMP TABLE collection_target(target_id bigint, source_id bigint, scope_description text)
""");
      jdbc.execute(
          """
          CREATE TEMP TABLE collection_check(check_id bigint, run_id bigint, target_id bigint,
            checked_at timestamptz, processing_status text, check_scope text, reason text)
          """);
      jdbc.update(
          """
          INSERT INTO collection_run VALUES
            (1,'2026-11-18T07:00Z','2026-11-18T07:30Z','success','v1'),
            (2,'2026-11-18T08:00Z','2026-11-18T08:30Z','partial_failure','v1'),
            (3,'2026-11-18T09:00Z','2026-11-18T11:00Z','success','v1')
          """);
      jdbc.update("INSERT INTO collection_target VALUES (1,11,'API'),(2,12,'공개 페이지')");
      jdbc.update(
          """
          INSERT INTO collection_check VALUES
            (10,1,1,'2026-11-18T07:05Z','success','target',NULL),
            (20,2,1,'2026-11-18T08:10Z','validation_failed','target','검증 실패'),
            (21,2,1,'2026-11-18T08:20Z','success','offer',NULL),
            (22,2,2,'2026-11-18T08:11Z','success','offer',NULL),
            (30,3,1,'2026-11-18T10:30Z','success','target',NULL)
          """);
      var repository = new CollectionRunRepository(new NamedParameterJdbcTemplate(jdbc));
      var cutoff = OffsetDateTime.parse("2026-11-18T10:00:00Z");
      var combined =
          new CollectionRunsRequestDto(
              cutoff.minusHours(2), cutoff.minusHours(1), 11L, "partial_failure", 50, null);

      assertThat(repository.find(combined, cutoff, null, null))
          .extracting(Run::id)
          .containsExactly(2L);
      var targets = repository.targets(List.of(2L), null, cutoff);
      assertThat(targets.get(0).result().processingStatus()).isEqualTo("VALIDATION_FAILED");
      assertThat(targets.get(0).result().lastSuccessAt())
          .isEqualTo(Instant.parse("2026-11-18T07:05:00Z"));
      assertThat(targets.get(1).result().processingStatus()).isEqualTo("SUCCESS");
      assertThat(repository.targets(List.of(2L), 12L, cutoff)).containsExactly(targets.get(1));

      var running = new CollectionRunsRequestDto(null, null, null, "running", 50, null);
      assertThat(repository.find(running, cutoff, null, null))
          .singleElement()
          .satisfies(
              run -> {
                assertThat(run.id()).isEqualTo(3);
                assertThat(run.finishedAt()).isNull();
              });
      assertThat(repository.targets(List.of(3L), null, cutoff)).isEmpty();
    } finally {
      ds.destroy();
    }
  }
}
