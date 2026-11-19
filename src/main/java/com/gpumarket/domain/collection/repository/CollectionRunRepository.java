package com.gpumarket.domain.collection.repository;

import com.gpumarket.domain.collection.dto.CollectionRunsRequestDto;
import com.gpumarket.domain.collection.dto.CollectionRunsResponseDto.CollectionTargetResultDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionRunRepository {
  public record Run(
      long id, Instant startedAt, Instant finishedAt, String status, String collectorVersion) {}

  public record Target(long runId, CollectionTargetResultDto result) {}

  private final NamedParameterJdbcTemplate jdbc;

  public CollectionRunRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Run> find(
      CollectionRunsRequestDto request,
      OffsetDateTime asOf,
      OffsetDateTime afterTime,
      Long afterId) {
    var params =
        new MapSqlParameterSource().addValue("asOf", asOf).addValue("size", request.limit() + 1);
    StringBuilder sql =
        new StringBuilder(
            """
SELECT r.run_id, r.started_at,
       CASE WHEN r.finished_at <= :asOf THEN r.finished_at END AS finished_at,
       state.run_status, r.collector_version
FROM collection_run r
CROSS JOIN LATERAL (
  SELECT CASE WHEN r.finished_at > :asOf THEN 'running' ELSE r.run_status END AS run_status
) state
WHERE r.started_at <= :asOf
""");
    if (request.from() != null) {
      params.addValue("fromTime", request.from());
      sql.append(" AND r.started_at >= :fromTime");
    }
    if (request.to() != null) {
      params.addValue("toTime", request.to());
      sql.append(" AND r.started_at < :toTime");
    }
    if (request.sourceId() != null) {
      params.addValue("sourceId", request.sourceId());
      sql.append('\n')
          .append(
              """
              AND EXISTS (
                SELECT 1 FROM collection_check c JOIN collection_target t USING (target_id)
                WHERE c.run_id = r.run_id AND c.checked_at <= :asOf AND t.source_id = :sourceId
              )
              """);
    }
    if (request.status() != null) {
      params.addValue("status", request.status());
      sql.append(" AND state.run_status = :status");
    }
    if (afterTime != null) {
      params.addValue("afterTime", afterTime).addValue("afterId", afterId);
      sql.append(" AND (r.started_at, r.run_id) < (:afterTime, :afterId)");
    }
    sql.append(" ORDER BY r.started_at DESC, r.run_id DESC LIMIT :size");
    return jdbc.query(
        sql.toString(),
        params,
        (rs, row) ->
            new Run(
                rs.getLong("run_id"),
                time(rs, "started_at"),
                time(rs, "finished_at"),
                upper(rs.getString("run_status")),
                rs.getString("collector_version")));
  }

  public List<Target> targets(List<Long> runIds, Long sourceId, OffsetDateTime asOf) {
    if (runIds.isEmpty()) {
      return List.of();
    }

    var params = new MapSqlParameterSource().addValue("runIds", runIds).addValue("asOf", asOf);
    String sourceFilter = "";
    if (sourceId != null) {
      params.addValue("sourceId", sourceId);
      sourceFilter = " AND t.source_id = :sourceId";
    }
    String sql =
        """
        WITH selected AS (
          SELECT DISTINCT ON (c.run_id, c.target_id)
                 c.run_id, c.target_id, t.source_id, t.scope_description,
                 c.check_id, c.checked_at, c.processing_status, c.reason
          FROM collection_check c JOIN collection_target t USING (target_id)
          WHERE c.run_id IN (:runIds) AND c.checked_at <= :asOf
          %s
          ORDER BY c.run_id, c.target_id, (c.check_scope = 'target') DESC,
                   c.checked_at DESC, c.check_id DESC
        )
        SELECT s.*, previous.last_success_at
        FROM selected s JOIN collection_run r USING (run_id)
        LEFT JOIN LATERAL (
          SELECT prior.checked_at AS last_success_at
          FROM collection_check prior JOIN collection_run earlier ON earlier.run_id = prior.run_id
          WHERE prior.target_id = s.target_id AND prior.processing_status = 'success'
            AND (prior.checked_at, prior.check_id) <= (s.checked_at, s.check_id)
            AND (earlier.started_at, earlier.run_id) <= (r.started_at, r.run_id)
          ORDER BY prior.checked_at DESC, prior.check_id DESC LIMIT 1
        ) previous ON true
        ORDER BY s.run_id, s.target_id
        """
            .formatted(sourceFilter);
    return jdbc.query(
        sql,
        params,
        (rs, row) ->
            new Target(
                rs.getLong("run_id"),
                new CollectionTargetResultDto(
                    Long.toString(rs.getLong("target_id")),
                    Long.toString(rs.getLong("source_id")),
                    rs.getString("scope_description"),
                    status(rs.getString("processing_status")),
                    time(rs, "checked_at"),
                    rs.getString("reason"),
                    time(rs, "last_success_at"))));
  }

  private static String status(String status) {
    return "unsupported_path".equals(status) ? "UNSUPPORTED" : upper(status);
  }

  private static String upper(String value) {
    return value.toUpperCase(Locale.ROOT);
  }

  private static Instant time(ResultSet rs, String field) throws SQLException {
    var time = rs.getObject(field, OffsetDateTime.class);
    return time == null ? null : time.toInstant();
  }
}
